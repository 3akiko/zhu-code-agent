package com.zhubao.integration;

import com.zhubao.agent.AgentRunner;
import com.zhubao.agent.AgentUi;
import com.zhubao.config.ProviderConfig;
import com.zhubao.conversation.ContentBlock;
import com.zhubao.conversation.Conversation;
import com.zhubao.conversation.Message;
import com.zhubao.llm.LlmClient;
import com.zhubao.llm.LlmClientFactory;
import com.zhubao.llm.MockHttpServer;
import com.zhubao.llm.StreamEvent;
import com.zhubao.llm.ToolSpec;
import com.zhubao.permission.PermissionChoice;
import com.zhubao.permission.PermissionManager;
import com.zhubao.session.Session;
import com.zhubao.session.SessionMeta;
import com.zhubao.session.SessionStore;
import com.zhubao.tool.PathGuard;
import com.zhubao.tool.SerialToolExecutor;
import com.zhubao.tool.ToolCall;
import com.zhubao.tool.ToolRegistry;
import com.zhubao.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2 mock LLM 端到端（checklist E1/E2）：
 * mock HTTP 服务器按请求次数返回 tool_use 序列 → 真实工具在临时工作区执行 → 回填 → end_turn。
 * 覆盖双协议、多工具串行、权限允许/拒绝/总是允许、路径越界、64KB 截断、恢复后继续工具循环。
 */
class AgentLoopIntegrationTest {

    @TempDir
    Path tmp;

    private static final class TestUi implements AgentUi {
        PermissionChoice choice = PermissionChoice.ALLOW;
        int askCount = 0;
        final List<String> toolCalls = new ArrayList<>();

        @Override
        public void onStep(String status) {
        }

        @Override
        public void onEvent(StreamEvent event) {
        }

        @Override
        public void onToolCall(ToolCall call) {
            toolCalls.add(call.name());
        }

        @Override
        public void onToolResult(ToolResult result, int previewLines) {
        }

        @Override
        public PermissionChoice askPermission(ToolCall call) {
            askCount++;
            return choice;
        }
    }

    /** Anthropic：请求1 = 两个 write_file 工具调用；请求2+ = 文本 end_turn */
    private static final String ANTHRO_TOOL_SSE = """
            event: message_start
            data: {"type":"message_start","message":{"id":"m","usage":{"input_tokens":8,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tu1","name":"write_file","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"path\\":\\"a.txt\\",\\"content\\":\\"hi\\"}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tu2","name":"write_file","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"path\\":\\"b.txt\\",\\"content\\":\\"yo\\"}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":1}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":4}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    private static final String ANTHRO_END_SSE = """
            event: message_start
            data: {"type":"message_start","message":{"id":"m","usage":{"input_tokens":8,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"done"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    private static final String OPENAI_TOOL_SSE = """
            data: {"id":"1","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"fc1","function":{"name":"write_file","arguments":""}}]},"finish_reason":null}]}

            data: {"id":"1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"path\\":\\"a.txt\\",\\"content\\":\\"hi\\"}"}}]},"finish_reason":null}]}

            data: {"id":"1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

            data: {"id":"1","choices":[],"usage":{"prompt_tokens":6,"completion_tokens":5}}

            data: [DONE]

            """;

    private static final String OPENAI_END_SSE = """
            data: {"id":"1","choices":[{"index":0,"delta":{"content":"done"},"finish_reason":"stop"}]}

            data: {"id":"1","choices":[],"usage":{"prompt_tokens":6,"completion_tokens":3}}

            data: [DONE]

            """;

    private ProviderConfig anthropicProvider(String baseUrl) {
        ProviderConfig cfg = new ProviderConfig();
        cfg.setName("claude");
        cfg.setProtocol("anthropic");
        cfg.setModel("claude-sonnet-4-5");
        cfg.setBaseUrl(baseUrl);
        cfg.setApiKey("test-key");
        return cfg;
    }

    private ProviderConfig openAiProvider(String baseUrl) {
        ProviderConfig cfg = new ProviderConfig();
        cfg.setName("openai");
        cfg.setProtocol("openai");
        cfg.setModel("gpt-4o");
        cfg.setBaseUrl(baseUrl);
        cfg.setApiKey("test-key");
        return cfg;
    }


    /** 创建可自引用的 mock 服务器：按请求次数选择 SSE（第 1 次工具调用，之后 end_turn） */
    private static MockHttpServer scriptedServer(java.util.function.Function<Integer, String> sseFor) throws java.io.IOException {
        final java.util.concurrent.atomic.AtomicReference<MockHttpServer> ref =
                new java.util.concurrent.atomic.AtomicReference<>();
        MockHttpServer server = new MockHttpServer((ex, body) ->
                MockHttpServer.writeSse(ex, sseFor.apply(ref.get().capturedBodies().size())));
        ref.set(server);
        return server;
    }

    private AgentRunner.Result run(LlmClient client, Conversation conversation, String userText,
                                   TestUi ui, PermissionManager pm) {
        ToolRegistry registry = new ToolRegistry(new PathGuard(tmp), 200);
        SerialToolExecutor executor = new SerialToolExecutor(registry, pm, ui, 5);
        List<ToolSpec> specs = registry.all().stream()
                .map(t -> new ToolSpec(t.name(), t.description(), t.inputSchema()))
                .toList();
        return AgentRunner.run(client, conversation, userText, specs, executor, ui, 60);
    }

    @Test
    void anthropicMultiToolLoopExecutesAndRefills() throws Exception {
        try (MockHttpServer server = scriptedServer(n -> n == 1 ? ANTHRO_TOOL_SSE : ANTHRO_END_SSE)) {
            Conversation conversation = new Conversation();
            TestUi ui = new TestUi();
            PermissionManager pm = new PermissionManager(new ToolRegistry(new PathGuard(tmp), 200));
            AgentRunner.Result r = run(LlmClientFactory.create(anthropicProvider(server.baseUrl())),
                    conversation, "创建两个文件", ui, pm);

            assertFalse(r.error(), r.errorMessage());
            assertEquals("done", r.text());
            // 真实工具在临时工作区执行
            assertEquals("hi", Files.readString(tmp.resolve("a.txt")));
            assertEquals("yo", Files.readString(tmp.resolve("b.txt")));
            // 一次性回填：assistant(2 个 tool_use) + user(2 个 tool_result)
            List<Message> msgs = conversation.getMessages();
            assertEquals(4, msgs.size());
            assertEquals(2, msgs.get(1).getBlocks().size());
            assertEquals(2, msgs.get(2).getBlocks().size());
            // 第二次请求回传 tool_result（含 tool_use_id）
            String secondBody = server.capturedBodies().get(1);
            assertTrue(secondBody.contains("tool_result"));
            assertTrue(secondBody.contains("tool_use_id"));
            assertTrue(secondBody.contains("hi"));
            // 回填的 tool_use 必须带完整参数 input（回归 P1：参数不得丢失）
            assertTrue(secondBody.contains("\"input\":{\"path\":\"a.txt\""));
            assertTrue(secondBody.contains("\"input\":{\"path\":\"b.txt\""));
        }
    }

    @Test
    void openAiFunctionCallLoopExecutes() throws Exception {
        try (MockHttpServer server = scriptedServer(n -> n == 1 ? OPENAI_TOOL_SSE : OPENAI_END_SSE)) {
            Conversation conversation = new Conversation();
            TestUi ui = new TestUi();
            PermissionManager pm = new PermissionManager(new ToolRegistry(new PathGuard(tmp), 200));
            AgentRunner.Result r = run(LlmClientFactory.create(openAiProvider(server.baseUrl())),
                    conversation, "写文件", ui, pm);

            assertFalse(r.error(), r.errorMessage());
            assertEquals("done", r.text());
            assertEquals("hi", Files.readString(tmp.resolve("a.txt")));
            // OpenAI 回传 role=tool 消息
            String secondBody = server.capturedBodies().get(1);
            assertTrue(secondBody.contains("\"role\":\"tool\""));
            assertTrue(secondBody.contains("\"tool_call_id\":\"fc1\""));
        }
    }

    @Test
    void permissionDeniedRefillsErrorAndNoSideEffect() throws Exception {
        try (MockHttpServer server = scriptedServer(n -> n == 1 ? ANTHRO_TOOL_SSE : ANTHRO_END_SSE)) {
            Conversation conversation = new Conversation();
            TestUi ui = new TestUi();
            ui.choice = PermissionChoice.DENY;
            PermissionManager pm = new PermissionManager(new ToolRegistry(new PathGuard(tmp), 200));
            AgentRunner.Result r = run(LlmClientFactory.create(anthropicProvider(server.baseUrl())),
                    conversation, "写文件", ui, pm);

            assertFalse(r.error(), r.errorMessage());
            assertFalse(Files.exists(tmp.resolve("a.txt")), "拒绝后不得创建文件");
            assertFalse(Files.exists(tmp.resolve("b.txt")));
            // tool_result 为 error 并回填
            ContentBlock.ToolResultBlock block =
                    (ContentBlock.ToolResultBlock) conversation.getMessages().get(2).getBlocks().get(0);
            assertTrue(block.isError());
            assertTrue(block.output().contains("拒绝"));
        }
    }

    @Test
    void alwaysAllowRememberedWithinProgramRun() throws Exception {
        try (MockHttpServer server = scriptedServer(n -> n == 1 ? ANTHRO_TOOL_SSE : ANTHRO_END_SSE)) {
            Conversation conversation = new Conversation();
            TestUi ui = new TestUi();
            ui.choice = PermissionChoice.ALLOW_ALWAYS;
            PermissionManager pm = new PermissionManager(new ToolRegistry(new PathGuard(tmp), 200));
            AgentRunner.Result r = run(LlmClientFactory.create(anthropicProvider(server.baseUrl())),
                    conversation, "写两个文件", ui, pm);

            assertFalse(r.error(), r.errorMessage());
            // 两个 write_file 参数不同 → 只问一次：第一个 ALLOW_ALWAYS 记忆的是 a.txt，
            // b.txt 参数不同本应再问，但这里同轮内先后执行——第一个 s 后 a.txt 记忆；
            // 因 b.txt 与 a.txt 参数不同仍会问 → askCount 应为 2
            assertEquals(2, ui.askCount, "参数不同应分别确认");
            assertEquals(List.of("write_file", "write_file"), ui.toolCalls);
        }
    }

    @Test
    void pathEscapeRejectedByGuardWithReadableError() throws Exception {
        // 模型尝试写 ../outside.txt → 越界拒绝；同轮继续到 end_turn
        String escapeSse = ANTHRO_TOOL_SSE.replace("a.txt", "../escape.txt")
                .replace("\\\"content\\\":\\\"hi\\\"", "\\\"content\\\":\\\"x\\\"");
        try (MockHttpServer server = scriptedServer(n -> n == 1 ? escapeSse : ANTHRO_END_SSE)) {
            Conversation conversation = new Conversation();
            TestUi ui = new TestUi();
            PermissionManager pm = new PermissionManager(new ToolRegistry(new PathGuard(tmp), 200));
            AgentRunner.Result r = run(LlmClientFactory.create(anthropicProvider(server.baseUrl())),
                    conversation, "写文件", ui, pm);

            assertFalse(r.error(), r.errorMessage());
            ContentBlock.ToolResultBlock block =
                    (ContentBlock.ToolResultBlock) conversation.getMessages().get(2).getBlocks().get(0);
            assertTrue(block.isError());
            assertTrue(block.output().contains("越界"));
            assertFalse(Files.exists(tmp.getParent().resolve("escape.txt")), "不得在工作区外创建文件");
        }
    }

    /** Anthropic：bash 大输出（70KB）工具调用 */
    private static final String ANTHRO_BASH_TOOL_SSE = """
            event: message_start
            data: {"type":"message_start","message":{"id":"m","usage":{"input_tokens":8,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tu1","name":"bash","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"command\\":\\"yes x | head -c 70000\\"}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":4}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    @Test
    void largeBashOutputTruncatedOnSave() throws Exception {
        try (MockHttpServer server = scriptedServer(n -> n == 1 ? ANTHRO_BASH_TOOL_SSE : ANTHRO_END_SSE)) {
            Conversation conversation = new Conversation();
            TestUi ui = new TestUi();
            PermissionManager pm = new PermissionManager(new ToolRegistry(new PathGuard(tmp), 200));
            AgentRunner.Result r = run(LlmClientFactory.create(anthropicProvider(server.baseUrl())),
                    conversation, "跑命令", ui, pm);
            assertFalse(r.error(), r.errorMessage());

            // 内存中完整（当前会话回填不截断）
            ContentBlock.ToolResultBlock inMemory =
                    (ContentBlock.ToolResultBlock) conversation.getMessages().get(2).getBlocks().get(0);
            assertEquals(70_000, inMemory.output().length());

            // 落盘截断
            SessionStore store = new SessionStore(tmp);
            SessionMeta meta = new SessionMeta("s9", Instant.now(), Instant.now(),
                    conversation.previewTitle(), conversation.messageCount(),
                    new com.zhubao.session.ProviderSnapshot("claude", "anthropic", "claude-sonnet-4-5", server.baseUrl()));
            store.save(new Session(meta, conversation.getMessages()));
            ContentBlock.ToolResultBlock persisted =
                    (ContentBlock.ToolResultBlock) store.load("s9").orElseThrow().getMessages().get(2).getBlocks().get(0);
            assertTrue(persisted.output().length() <= SessionStore.TOOL_RESULT_PERSIST_CAP + 40);
            assertTrue(persisted.output().contains("已截断"));
        }
    }

    @Test
    void resumeSessionContinuesToolLoop() throws Exception {
        try (MockHttpServer server = scriptedServer(n -> n == 1 ? ANTHRO_TOOL_SSE : ANTHRO_END_SSE)) {
            // 第一轮：建文件 + 落盘
            Conversation conversation = new Conversation();
            TestUi ui = new TestUi();
            PermissionManager pm = new PermissionManager(new ToolRegistry(new PathGuard(tmp), 200));
            run(LlmClientFactory.create(anthropicProvider(server.baseUrl())), conversation, "写文件", ui, pm);

            SessionStore store = new SessionStore(tmp);
            SessionMeta meta = new SessionMeta("s10", Instant.now(), Instant.now(),
                    conversation.previewTitle(), conversation.messageCount(),
                    new com.zhubao.session.ProviderSnapshot("claude", "anthropic", "claude-sonnet-4-5", server.baseUrl()));
            store.save(new Session(meta, conversation.getMessages()));
            assertEquals(4, store.load("s10").orElseThrow().getMessages().size());

            // 第二轮：恢复会话继续（请求会回传历史 tool 消息）
            Conversation restored = new Conversation(store.load("s10").orElseThrow().getMessages());
            TestUi ui2 = new TestUi();
            AgentRunner.Result r2 = run(LlmClientFactory.create(anthropicProvider(server.baseUrl())),
                    restored, "继续", ui2, new PermissionManager(new ToolRegistry(new PathGuard(tmp), 200)));
            assertFalse(r2.error(), r2.errorMessage());
            assertEquals("done", r2.text());
            String thirdBody = server.capturedBodies().get(2);
            assertTrue(thirdBody.contains("tool_use"), "恢复后应回传历史工具上下文");
            assertTrue(thirdBody.contains("\"input\":{\"path\":\"a.txt\""),
                    "恢复后 tool_use 参数必须完整（回归 P1）");
        }
    }
}
