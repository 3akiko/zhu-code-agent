package com.zhubao.integration;

import com.zhubao.agent.AgentRunner;
import com.zhubao.agent.AgentUi;
import com.zhubao.config.ProviderConfig;
import com.zhubao.context.CompactionOptions;
import com.zhubao.context.CompactionResult;
import com.zhubao.context.ContextCompactor;
import com.zhubao.conversation.ContentBlock;
import com.zhubao.conversation.Conversation;
import com.zhubao.conversation.Message;
import com.zhubao.conversation.Role;
import com.zhubao.llm.LlmClient;
import com.zhubao.llm.LlmClientFactory;
import com.zhubao.llm.LlmStream;
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

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M4 mock 端到端（checklist：统计/告警/压缩/中断）：
 * 真实 LlmClient + MockHttpServer + 真实工具 + SessionStore。
 */
class M4ContextIntegrationTest {

    @TempDir
    Path tmp;

    private static final class TestUi implements AgentUi {
        PermissionChoice choice = PermissionChoice.ALLOW;
        final List<StreamEvent> events = new ArrayList<>();
        final AtomicBoolean sawDelta = new AtomicBoolean(false);

        @Override
        public void onStep(String status) {
        }

        @Override
        public synchronized void onEvent(StreamEvent event) {
            events.add(event);
            if (event instanceof StreamEvent.TextDelta) {
                sawDelta.set(true);
            }
        }

        @Override
        public void onToolCall(ToolCall call) {
        }

        @Override
        public void onToolResult(ToolResult result, int previewLines) {
        }

        @Override
        public PermissionChoice askPermission(ToolCall call) {
            return choice;
        }
    }

    private ProviderConfig openAiProvider(String baseUrl) {
        ProviderConfig cfg = new ProviderConfig();
        cfg.setName("deepseek");
        cfg.setProtocol("openai");
        cfg.setModel("deepseek-v4-flash");
        cfg.setBaseUrl(baseUrl);
        cfg.setApiKey("test-key");
        return cfg;
    }

    private ProviderConfig anthropicProvider(String baseUrl) {
        ProviderConfig cfg = new ProviderConfig();
        cfg.setName("claude");
        cfg.setProtocol("anthropic");
        cfg.setModel("claude-sonnet-4-5");
        cfg.setBaseUrl(baseUrl);
        cfg.setApiKey("test-key");
        return cfg;
    }

    private record Harness(ToolRegistry registry, PermissionManager permissions, SerialToolExecutor executor,
                           List<ToolSpec> specs, Conversation conversation, TestUi ui) {
    }

    private Harness harness() {
        ToolRegistry registry = new ToolRegistry(new PathGuard(tmp), 200);
        PermissionManager permissions = new PermissionManager(registry);
        TestUi ui = new TestUi();
        SerialToolExecutor executor = new SerialToolExecutor(registry, permissions, ui, 5);
        List<ToolSpec> specs = registry.all().stream()
                .map(t -> new ToolSpec(t.name(), t.description(), t.inputSchema()))
                .toList();
        return new Harness(registry, permissions, executor, specs, new Conversation(), ui);
    }

    private static final String OPENAI_TOOL_SSE = """
            data: {"id":"1","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_file","arguments":""}}]},"finish_reason":null}]}

            data: {"id":"1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"path\\":\\"a.txt\\"}"}}]},"finish_reason":"tool_calls"}]}

            data: {"id":"1","choices":[],"usage":{"prompt_tokens":7,"completion_tokens":5,"total_tokens":12,"prompt_tokens_details":{"cached_tokens":60}}}

            data: [DONE]

            """;

    private static final String OPENAI_END_SSE = """
            data: {"id":"1","choices":[{"index":0,"delta":{"content":"完成"},"finish_reason":"stop"}]}

            data: {"id":"1","choices":[],"usage":{"prompt_tokens":9,"completion_tokens":7,"total_tokens":16,"prompt_tokens_details":{"cached_tokens":90}}}

            data: [DONE]

            """;

    private static final String OPENAI_SUMMARY_SSE = """
            data: {"id":"1","choices":[{"index":0,"delta":{"content":"摘要：旧轮次要点"},"finish_reason":"stop"}]}

            data: {"id":"1","choices":[],"usage":{"prompt_tokens":1,"completion_tokens":2}}

            data: [DONE]

            """;

    @Test
    void tokenStatsAndCacheAccumulatedAndPersisted() throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "content");
        Harness h = harness();
        try (MockHttpServer server = new MockHttpServer(scriptedOpenAi(List.of(OPENAI_TOOL_SSE, OPENAI_END_SSE)))) {
            LlmClient client = LlmClientFactory.create(openAiProvider(server.baseUrl()));
            AgentRunner.Result r = AgentRunner.run(client, h.conversation, "读文件", h.specs, h.executor, h.ui, 60);
            assertFalse(r.error(), r.errorMessage());
            // 本轮两步骤求和：7+9=16 in，5+7=12 out；最后一步缓存命中 90
            assertEquals(16, r.totalInputTokens());
            assertEquals(12, r.totalOutputTokens());
            assertEquals(90, r.cacheReadTokens());
            // 落盘累计
            SessionMeta meta = new SessionMeta("s1", Instant.now(), Instant.now(), "标题",
                    h.conversation.messageCount(), new com.zhubao.session.ProviderSnapshot(
                            "deepseek", "openai", "deepseek-v4-flash", "http://x"), r.totalInputTokens(), r.totalOutputTokens());
            SessionStore store = new SessionStore(tmp.resolve("sessions"));
            store.save(new Session(meta, h.conversation.getMessages()));
            Session reloaded = store.load("s1").orElseThrow();
            assertEquals(16, reloaded.getMeta().totalInputTokens());
            assertEquals(12, reloaded.getMeta().totalOutputTokens());
        }
    }

    @Test
    void compactionFoldsThenDoubleProtocolRefillStillWorks() throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "old");
        // 构造多轮会话
        Conversation conv = new Conversation(List.of(
                new Message(Role.USER, "q1"),
                new Message(Role.ASSISTANT, List.of(new ContentBlock.ToolUseBlock("t1", "read_file", "{\"path\":\"a.txt\"}")), null, null),
                new Message(Role.USER, List.of(new ContentBlock.ToolResultBlock("t1", "read_file", false, "old 内容")), null, null),
                new Message(Role.ASSISTANT, "第一轮答复"),
                new Message(Role.USER, "q2"),
                new Message(Role.ASSISTANT, "第二轮答复")));

        // 压缩：OpenAI 客户端返回摘要
        try (MockHttpServer server = new MockHttpServer((ex, body) -> MockHttpServer.writeSse(ex, OPENAI_SUMMARY_SSE))) {
            ContextCompactor compactor = new ContextCompactor();
            CompactionResult cr = compactor.compact(conv, LlmClientFactory.create(openAiProvider(server.baseUrl())),
                    new CompactionOptions(0.6, true, 1, 1024), null);
            assertFalse(cr.isError(), cr.errorMessage());
            assertTrue(cr.compacted());
            assertEquals(1, cr.foldedTurns());
            List<Message> msgs = conv.getMessages();
            assertEquals(2, msgs.size());
            assertTrue(msgs.get(0).getContent().startsWith("【上下文已压缩】"));
            assertTrue(msgs.get(0).getContent().contains("q2"));
            assertEquals(Role.ASSISTANT, msgs.get(1).getRole());

            // 压缩后可保存/加载
            SessionStore store = new SessionStore(tmp.resolve("sessions"));
            SessionMeta meta = new SessionMeta("c1", Instant.now(), Instant.now(), "标题",
                    conv.messageCount(), new com.zhubao.session.ProviderSnapshot(
                            "deepseek", "openai", "deepseek-v4-flash", "http://x"), 0, 0);
            store.save(new Session(meta, conv.getMessages()));
            assertEquals(2, store.load("c1").orElseThrow().getMessages().size());
        }

        // 压缩后 Anthropic 协议继续一轮工具回填（双协议不回归）
        Harness h = harness();
        h.conversation.replaceMessages(conv.getMessages());
        String toolSse = """
                event: message_start
                data: {"type":"message_start","message":{"id":"m","usage":{"input_tokens":3,"output_tokens":1}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tu1","name":"read_file","input":{}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"path\\":\\"a.txt\\"}"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":2,"cache_read_input_tokens":40}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        String endSse = """
                event: message_start
                data: {"type":"message_start","message":{"id":"m","usage":{"input_tokens":3,"output_tokens":1}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"继续完成"}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":4,"cache_read_input_tokens":41}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        try (MockHttpServer server = new MockHttpServer(scriptedAnthropic(List.of(toolSse, endSse)))) {
            LlmClient client = LlmClientFactory.create(anthropicProvider(server.baseUrl()));
            AgentRunner.Result r = AgentRunner.run(client, h.conversation, "继续", h.specs, h.executor, h.ui, 60);
            assertFalse(r.error(), r.errorMessage());
            assertTrue(r.text().contains("继续完成"));
            assertEquals(41, r.cacheReadTokens());
            // 回填后结构仍合法：user(摘要) / assistant / user(继续) / assistant(tool_use) / user(tool_result) / assistant(完成)
            List<Message> msgs = h.conversation.getMessages();
            assertEquals(6, msgs.size());
            for (int i = 0; i < msgs.size(); i++) {
                assertEquals(i % 2 == 0 ? Role.USER : Role.ASSISTANT, msgs.get(i).getRole(), "消息序应交替");
            }
            assertInstanceOf(ContentBlock.ToolUseBlock.class, msgs.get(3).getBlocks().get(0));
        }
    }

    @Test
    void interruptCancelsGenerationRollsBackAndCanContinue() throws Exception {
        Harness h = harness();
        CountDownLatch releaseServer = new CountDownLatch(1);
        String firstChunk = "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"半成品\"},\"finish_reason\":null}]}\n\n";

        AtomicReference<LlmStream> streamRef = new AtomicReference<>();
        AtomicReference<AgentRunner.Result> resultRef = new AtomicReference<>();
        AtomicBoolean runFinished = new AtomicBoolean(false);

        try (MockHttpServer server = new MockHttpServer((ex, body) -> {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            OutputStream os = ex.getResponseBody();
            os.write(firstChunk.getBytes(StandardCharsets.UTF_8));
            os.flush();
            try {
                releaseServer.await(10, TimeUnit.SECONDS); // 保持连接打开，等测试取消
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            os.close();
        })) {
            LlmClient client = LlmClientFactory.create(openAiProvider(server.baseUrl()));
            Thread t = new Thread(() -> {
                AgentRunner.Result r = AgentRunner.run(client, h.conversation, "问", h.specs, h.executor, h.ui, 60,
                        AgentRunner.STEP_IDLE_TIMEOUT_MS, streamRef::set);
                resultRef.set(r);
                runFinished.set(true);
            });
            t.start();

            // 等首个 delta 到达，然后模拟 Ctrl+C：取消当前流
            long deadline = System.currentTimeMillis() + 5_000;
            while (!h.ui.sawDelta.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(h.ui.sawDelta.get(), "应收到半成品文本");
            LlmStream stream = streamRef.get();
            assertNotNull(stream);
            stream.cancel();
            releaseServer.countDown();
            t.join(5_000);
            assertFalse(t.isAlive(), "取消后本轮应快速返回");

            AgentRunner.Result r = resultRef.get();
            assertNotNull(r);
            assertTrue(r.interrupted(), "取消后应标记中断");
            // 半成品不写入会话：仅保留用户消息
            assertEquals(1, h.conversation.messageCount());
            assertFalse(h.conversation.getMessages().get(0).getContent().contains("半成品"));

            // 模拟 ChatApp 写入 assistant「（已中断）」标记（变更控制：保持交替）
            h.conversation.addAssistant("（已中断）", null, null);
            assertEquals(2, h.conversation.messageCount());
            assertEquals(Role.ASSISTANT, h.conversation.getMessages().get(1).getRole());
        }

        // 中断后可继续正常一轮（OpenAI）
        try (MockHttpServer server = new MockHttpServer(scriptedOpenAi(List.of(OPENAI_END_SSE)))) {
            LlmClient client = LlmClientFactory.create(openAiProvider(server.baseUrl()));
            AgentRunner.Result r2 = AgentRunner.run(client, h.conversation, "继续", h.specs, h.executor, h.ui, 60);
            assertFalse(r2.error(), r2.errorMessage());
            assertTrue(r2.text().contains("完成"));
            List<Message> msgs = h.conversation.getMessages();
            assertEquals(Role.USER, msgs.get(msgs.size() - 2).getRole());
            assertEquals(Role.ASSISTANT, msgs.get(msgs.size() - 1).getRole());
        }
    }

    /** 按请求次数返回脚本的 OpenAI mock handler */
    private MockHttpServer.ThrowingHandler scriptedOpenAi(List<String> sseScript) {
        int[] count = {0};
        return (ex, body) -> {
            int idx = Math.min(count[0]++, sseScript.size() - 1);
            MockHttpServer.writeSse(ex, sseScript.get(idx));
        };
    }

    /** 按请求次数返回脚本的 Anthropic mock handler */
    private MockHttpServer.ThrowingHandler scriptedAnthropic(List<String> sseScript) {
        int[] count = {0};
        return (ex, body) -> {
            int idx = Math.min(count[0]++, sseScript.size() - 1);
            MockHttpServer.writeSse(ex, sseScript.get(idx));
        };
    }
}
