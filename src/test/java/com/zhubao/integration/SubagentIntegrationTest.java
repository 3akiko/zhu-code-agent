package com.zhubao.integration;

import com.zhubao.agent.AgentRunner;
import com.zhubao.agent.AgentUi;
import com.zhubao.agent.SubagentCoordinator;
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
import com.zhubao.tool.ParallelToolExecutor;
import com.zhubao.tool.PathGuard;
import com.zhubao.tool.TaskTool;
import com.zhubao.tool.ToolCall;
import com.zhubao.tool.ToolRegistry;
import com.zhubao.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M5（spec F3/AC3）端到端：父 agent 通过 task 工具派生子任务——独立会话执行、摘要回填、
 * 并行子任务、深度封顶工具池裁剪、权限继承（父已批准不二次确认）、级联中断、父计数只计 task。
 *
 * <p>单子任务场景按「请求计数」选脚本（父后续请求体含之前 tool_use 的 prompt，body 区分不可靠）；
 * 并行子任务按 body 区分（子任务 prompt 各不相同，且父摘要不含 prompt）。
 */
class SubagentIntegrationTest {

    @TempDir
    Path ws;

    @BeforeEach
    void resetClientCache() {
        LlmClientFactory.resetCache();
    }

    private static final class RecordingUi implements AgentUi {
        final List<String> toolCalls = new ArrayList<>();
        final List<String> subtaskStarts = new ArrayList<>();
        final List<String> subtaskEnds = new ArrayList<>();
        int askPermissionCount;

        @Override public void onStep(String status) { }
        @Override public void onEvent(StreamEvent event) { }
        @Override public void onToolCall(ToolCall call) { toolCalls.add(call.name()); }
        @Override public void onToolResult(ToolResult result, int previewLines) { }
        @Override public PermissionChoice askPermission(ToolCall call) { askPermissionCount++; return PermissionChoice.ALLOW; }
        @Override public void onSubtaskStart(int id, String promptPreview) { subtaskStarts.add(id + ":" + promptPreview); }
        @Override public void onSubtaskEnd(int id, String summary) { subtaskEnds.add(id + ":" + summary); }
    }

    private ProviderConfig anthropicProvider(String baseUrl) {
        ProviderConfig cfg = new ProviderConfig();
        cfg.setName("claude-m5");
        cfg.setProtocol("anthropic");
        cfg.setModel("claude-sonnet-4-5");
        cfg.setBaseUrl(baseUrl);
        cfg.setApiKey("test-key");
        return cfg;
    }

    private ProviderConfig openAiProvider(String baseUrl) {
        ProviderConfig cfg = new ProviderConfig();
        cfg.setName("openai-m5");
        cfg.setProtocol("openai");
        cfg.setModel("gpt-4o");
        cfg.setBaseUrl(baseUrl);
        cfg.setApiKey("test-key");
        return cfg;
    }

    private record Harness(Conversation conversation, RecordingUi ui, ToolRegistry registry,
                           PermissionManager permissions, ParallelToolExecutor executor,
                           TaskTool taskTool, List<ToolSpec> specs) {
    }

    private Harness harness(LlmClient client) {
        ToolRegistry registry = new ToolRegistry(new PathGuard(ws), 200);
        PermissionManager pm = new PermissionManager(registry);
        RecordingUi ui = new RecordingUi();
        TaskTool taskTool = new TaskTool();
        taskTool.setClientProvider(() -> client);
        taskTool.setRegistry(registry);
        taskTool.setPermissions(pm);
        taskTool.setUi(ui);
        taskTool.setMaxDepth(2);
        taskTool.setMaxParallel(4);
        taskTool.setMaxSteps(30);
        taskTool.setPreviewLines(5);
        registry.registerExternal(taskTool);
        ParallelToolExecutor executor = new ParallelToolExecutor(registry, pm, ui, 5);
        List<ToolSpec> specs = registry.all().stream()
                .map(t -> new ToolSpec(t.name(), t.description(), t.inputSchema()))
                .toList();
        return new Harness(new Conversation(), ui, registry, pm, executor, taskTool, specs);
    }

    /** 按请求次数选 SSE（第 1 次请求 size=1）；可旁路记录指定请求体 */
    private static MockHttpServer scripted(java.util.function.Function<Integer, String> sseFor) throws IOException {
        final AtomicReference<MockHttpServer> ref = new AtomicReference<>();
        MockHttpServer server = new MockHttpServer((ex, body) ->
                MockHttpServer.writeSse(ex, sseFor.apply(ref.get().capturedBodies().size())));
        ref.set(server);
        return server;
    }

    // ── Anthropic SSE ──────────────────────────────────────
    private static final String ANTHRO_TASK_SSE = """
            event: message_start
            data: {"type":"message_start","message":{"id":"m","usage":{"input_tokens":8,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tu1","name":"task","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"prompt\\":\\"调研\\"}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":4}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    private static String anthroTextSse(String text) {
        return """
                event: message_start
                data: {"type":"message_start","message":{"id":"m","usage":{"input_tokens":8,"output_tokens":1}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"%s"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}

                event: message_stop
                data: {"type":"message_stop"}

                """.formatted(text);
    }

    private static final String ANTHRO_SUB_WRITE_SSE = """
            event: message_start
            data: {"type":"message_start","message":{"id":"m","usage":{"input_tokens":8,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"sw1","name":"write_file","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"path\\":\\"sub.txt\\",\\"content\\":\\"by-sub\\"}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":4}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    // ── OpenAI SSE（并行） ─────────────────────────────────
    private static final String OPENAI_TASK_TWO_SSE = """
            data: {"id":"1","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"t1","function":{"name":"task","arguments":""}}]},"finish_reason":null}]}

            data: {"id":"1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"prompt\\":\\"调研A\\"}"}}]},"finish_reason":null}]}

            data: {"id":"1","choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"t2","function":{"name":"task","arguments":""}}]},"finish_reason":null}]}

            data: {"id":"1","choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"function":{"arguments":"{\\"prompt\\":\\"调研B\\"}"}}]},"finish_reason":"tool_calls"}]}

            data: {"id":"1","choices":[],"usage":{"prompt_tokens":6,"completion_tokens":5}}

            data: [DONE]

            """;

    private static String openAiTextSse(String text) {
        return """
                data: {"id":"1","choices":[{"index":0,"delta":{"content":"%s"},"finish_reason":"stop"}]}

                data: {"id":"1","choices":[],"usage":{"prompt_tokens":6,"completion_tokens":3}}

                data: [DONE]

                """.formatted(text);
    }

    @Test
    void anthropicParentSpawnsSubtaskGetsSummaryThenContinues() throws Exception {
        // 请求计数：1=父 task；2=子任务文本；3+=父收尾
        try (MockHttpServer server = scripted(n -> {
            if (n == 1) {
                return ANTHRO_TASK_SSE;
            }
            if (n == 2) {
                return anthroTextSse("子任务报告");
            }
            return anthroTextSse("父综合结果");
        })) {
            Harness h = harness(LlmClientFactory.create(anthropicProvider(server.baseUrl())));
            AgentRunner.Result r = AgentRunner.run(LlmClientFactory.create(anthropicProvider(server.baseUrl())),
                    h.conversation, "派子任务调研并汇总", h.specs, h.executor, h.ui, 60);

            assertFalse(r.error(), r.errorMessage());
            assertEquals("父综合结果", r.text());
            // 会话：user + assistant(tool_use task) + user(tool_result 摘要) + assistant(父结果)
            List<Message> msgs = h.conversation.getMessages();
            assertEquals(4, msgs.size());
            assertInstanceOf(ContentBlock.ToolUseBlock.class, msgs.get(1).getBlocks().get(0));
            ContentBlock.ToolResultBlock resultBlock =
                    (ContentBlock.ToolResultBlock) msgs.get(2).getBlocks().get(0);
            assertEquals("task", resultBlock.name());
            assertFalse(resultBlock.isError(), resultBlock.output());
            assertTrue(resultBlock.output().contains("[task#1] 状态=完成"), resultBlock.output());
            assertTrue(resultBlock.output().contains("子任务报告"), resultBlock.output());
            assertEquals(1, h.ui.subtaskStarts.size());
            assertEquals(1, h.ui.subtaskEnds.size());
        }
    }

    @Test
    void openAiParallelSubtasksRunConcurrently() throws Exception {
        // 并行：body 区分（子任务 prompt 各异；父摘要不含 prompt）
        try (MockHttpServer server = new MockHttpServer((ex, body) -> {
            // 父后续请求含历史 tool_calls（assistant 工具调用），子任务请求（历史无 tool_calls）按 prompt 区分
            if (body.contains("\"tool_calls\"")) {
                MockHttpServer.writeSse(ex, openAiTextSse("父并行汇总"));
            } else if (body.contains("调研A")) {
                MockHttpServer.writeSse(ex, openAiTextSse("报告A"));
            } else if (body.contains("调研B")) {
                MockHttpServer.writeSse(ex, openAiTextSse("报告B"));
            } else if (body.contains("并行派")) {
                MockHttpServer.writeSse(ex, OPENAI_TASK_TWO_SSE);
            } else {
                MockHttpServer.writeSse(ex, openAiTextSse("父并行汇总"));
            }
        })) {
            Harness h = harness(LlmClientFactory.create(openAiProvider(server.baseUrl())));
            AgentRunner.Result r = AgentRunner.run(LlmClientFactory.create(openAiProvider(server.baseUrl())),
                    h.conversation, "并行派两个子任务", h.specs, h.executor, h.ui, 60);

            assertFalse(r.error(), r.errorMessage());
            assertEquals("父并行汇总", r.text());
            List<Message> msgs = h.conversation.getMessages();
            ContentBlock.ToolResultBlock blockA = (ContentBlock.ToolResultBlock) msgs.get(2).getBlocks().get(0);
            ContentBlock.ToolResultBlock blockB = (ContentBlock.ToolResultBlock) msgs.get(2).getBlocks().get(1);
            assertTrue(blockA.output().contains("报告A"), blockA.output());
            assertTrue(blockB.output().contains("报告B"), blockB.output());
            assertEquals(2, h.ui.subtaskStarts.size());
            assertEquals(2, h.ui.subtaskEnds.size());
        }
    }

    @Test
    void depthLimitPrunesTaskToolFromSubtaskToolset() throws Exception {
        AtomicReference<String> subtaskBody = new AtomicReference<>();
        // 请求计数：1=父 task；2=子任务请求（捕获 body 断言工具池裁剪）；3=子任务文本；4+=父收尾
        try (MockHttpServer server = new MockHttpServer((ex, body) -> {
            int n = 0;
            // 通过 capturedBodies 计数需自引用；这里用显式计数器
        })) {
            // 占位（改用 scripted + 旁路记录）
        }
        AtomicReference<MockHttpServer> ref = new AtomicReference<>();
        try (MockHttpServer server = new MockHttpServer((ex, body) -> {
            int n = ref.get().capturedBodies().size();
            if (n == 1) {
                MockHttpServer.writeSse(ex, ANTHRO_TASK_SSE);
            } else if (n == 2) {
                subtaskBody.set(body);
                MockHttpServer.writeSse(ex, anthroTextSse("子任务收尾"));
            } else {
                MockHttpServer.writeSse(ex, anthroTextSse("父收尾"));
            }
        })) {
            ref.set(server);
            Harness h = harness(LlmClientFactory.create(anthropicProvider(server.baseUrl())));
            h.taskTool.setMaxDepth(1); // 父(0) → 子(1) 封顶：子任务工具池不含 task
            AgentRunner.Result r = AgentRunner.run(LlmClientFactory.create(anthropicProvider(server.baseUrl())),
                    h.conversation, "派子任务", h.specs, h.executor, h.ui, 60);

            assertFalse(r.error(), r.errorMessage());
            // 子任务请求体的 tools 定义不含 task（工具池裁剪，spec F3.5）
            String subBody = subtaskBody.get();
            assertNotNull(subBody);
            assertFalse(subBody.contains("\"name\":\"task\""), "深度封顶子任务工具池不应注册 task: " + subBody);
            assertTrue(subBody.contains("\"name\":\"read_file\""), "子任务仍应保留普通工具");
            // 父正常收尾
            assertEquals("父收尾", r.text());
        }
    }

    @Test
    void parentApprovedPermissionInheritedBySubtask() throws Exception {
        // 请求计数：1=父 task；2=子 write；3=子文本；4+=父收尾
        try (MockHttpServer server = scripted(n -> {
            if (n == 1) {
                return ANTHRO_TASK_SSE;
            }
            if (n == 2) {
                return ANTHRO_SUB_WRITE_SSE;
            }
            if (n == 3) {
                return anthroTextSse("子任务报告");
            }
            return anthroTextSse("父收尾");
        })) {
            Harness h = harness(LlmClientFactory.create(anthropicProvider(server.baseUrl())));
            // 父先「总是允许」write_file sub.txt → 子任务内同名操作自动放行、不二次确认
            h.permissions.rememberAlways(new ToolCall("p1", "write_file",
                    ToolCall.json(Map.of("path", "sub.txt", "content", "ignored"))));

            AgentRunner.Result r = AgentRunner.run(LlmClientFactory.create(anthropicProvider(server.baseUrl())),
                    h.conversation, "派子任务", h.specs, h.executor, h.ui, 60);

            assertFalse(r.error(), r.errorMessage());
            assertEquals(0, h.ui.askPermissionCount, "父已批准的子任务操作不应二次确认");
            assertEquals("by-sub", Files.readString(ws.resolve("sub.txt")));
        }
    }

    @Test
    void interruptCascadesToSubtasks() throws Exception {
        CountDownLatch releaseSubtask = new CountDownLatch(1);
        try (MockHttpServer server = new MockHttpServer((ex, body) -> {
            if (body.contains("调研")) {
                // 子任务请求挂起：保持连接不返回，等待级联取消
                ex.getResponseHeaders().set("Content-Type", "text/event-stream");
                ex.sendResponseHeaders(200, 0);
                OutputStream os = ex.getResponseBody();
                try {
                    releaseSubtask.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                os.close();
            } else if (body.contains("派子")) {
                MockHttpServer.writeSse(ex, ANTHRO_TASK_SSE);
            } else {
                MockHttpServer.writeSse(ex, anthroTextSse("父收尾"));
            }
        })) {
            Harness h = harness(LlmClientFactory.create(anthropicProvider(server.baseUrl())));
            AtomicReference<AgentRunner.Result> resultRef = new AtomicReference<>();
            AtomicBoolean finished = new AtomicBoolean();
            Thread worker = new Thread(() -> {
                resultRef.set(AgentRunner.run(LlmClientFactory.create(anthropicProvider(server.baseUrl())),
                        h.conversation, "派子任务", h.specs, h.executor, h.ui, 60));
                finished.set(true);
            });
            worker.setDaemon(true);
            worker.start();

            // 等子任务启动（折叠单行出现），模拟 Ctrl+C：级联取消（interrupt 子任务线程 + 主线程）
            long deadline = System.currentTimeMillis() + 5_000;
            while (h.ui.subtaskStarts.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertFalse(h.ui.subtaskStarts.isEmpty(), "子任务应已启动");
            SubagentCoordinator.cancelAll();
            worker.interrupt();
            releaseSubtask.countDown();
            worker.join(8_000);
            assertFalse(worker.isAlive(), "级联取消后本轮应快速返回");

            AgentRunner.Result r = resultRef.get();
            assertNotNull(r);
            assertTrue(r.interrupted(), "级联中断应使本轮中断，实际 " + r.errorMessage());
            assertTrue(SubagentCoordinator.activeCount() == 0, "在途子任务应全部注销");
        }
    }

    @Test
    void parentStepBudgetCountsTaskCallOnce() throws Exception {
        // 父 maxCallsPerTurn=2：task(1) + 父 end_turn(2)；子任务内部 2 步（write + 文本）不消耗父计数
        try (MockHttpServer server = scripted(n -> {
            if (n == 1) {
                return ANTHRO_TASK_SSE;
            }
            if (n == 2) {
                return ANTHRO_SUB_WRITE_SSE;
            }
            if (n == 3) {
                return anthroTextSse("子任务报告");
            }
            return anthroTextSse("父收尾");
        })) {
            Harness h = harness(LlmClientFactory.create(anthropicProvider(server.baseUrl())));
            AgentRunner.Result r = AgentRunner.run(LlmClientFactory.create(anthropicProvider(server.baseUrl())),
                    h.conversation, "派子任务", h.specs, h.executor, h.ui, 2);

            assertFalse(r.error(), r.errorMessage());
            assertFalse(r.limitReached(), "子任务内部步骤不应消耗父 max_calls_per_turn");
            assertEquals("父收尾", r.text());
            assertEquals("by-sub", Files.readString(ws.resolve("sub.txt")));
        }
    }

    @Test
    void nestedDepthPrunesTaskAtGrandchild() throws Exception {
        // M5 review 复现：maxDepth=2（父→子→孙封顶）。孙 agent 请求体的 tools 必须不含 task。
        // 请求计数：1=父 task；2=子 task（尝试派孙）；3=孙请求（捕获 body）；4+=收尾
        AtomicReference<MockHttpServer> ref = new AtomicReference<>();
        AtomicReference<String> grandchildBody = new AtomicReference<>();
        try (MockHttpServer server = new MockHttpServer((ex, body) -> {
            int n = ref.get().capturedBodies().size();
            if (n == 1) {
                MockHttpServer.writeSse(ex, ANTHRO_TASK_SSE);
            } else if (n == 2) {
                MockHttpServer.writeSse(ex, ANTHRO_TASK_SSE);
            } else if (n == 3) {
                grandchildBody.set(body);
                MockHttpServer.writeSse(ex, anthroTextSse("孙收尾"));
            } else {
                MockHttpServer.writeSse(ex, anthroTextSse("收尾"));
            }
        })) {
            ref.set(server);
            Harness h = harness(LlmClientFactory.create(anthropicProvider(server.baseUrl())));
            h.taskTool.setMaxDepth(2);
            AgentRunner.Result r = AgentRunner.run(LlmClientFactory.create(anthropicProvider(server.baseUrl())),
                    h.conversation, "派子任务", h.specs, h.executor, h.ui, 60);
            assertFalse(r.error(), r.errorMessage());
            String body = grandchildBody.get();
            assertNotNull(body, "应捕获到孙 agent 请求体");
            assertFalse(body.contains("\"name\":\"task\""),
                    "深度封顶的孙 agent 工具池不应注册 task（深度沿执行链传递失效）: " + body);
            assertTrue(body.contains("\"name\":\"read_file\""), "孙 agent 仍应保留普通工具");
        }
    }
}
