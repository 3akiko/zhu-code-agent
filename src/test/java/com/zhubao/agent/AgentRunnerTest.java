package com.zhubao.agent;

import com.zhubao.conversation.ContentBlock;
import com.zhubao.conversation.Conversation;
import com.zhubao.conversation.Message;
import com.zhubao.conversation.Role;
import com.zhubao.llm.ChatRequest;
import com.zhubao.llm.LlmClient;
import com.zhubao.llm.LlmStream;
import com.zhubao.llm.StreamEvent;
import com.zhubao.llm.ToolSpec;
import com.zhubao.permission.PermissionChoice;
import com.zhubao.permission.PermissionManager;
import com.zhubao.tool.PathGuard;
import com.zhubao.tool.PlanModeExecutor;
import com.zhubao.tool.SerialToolExecutor;
import com.zhubao.tool.ToolCall;
import com.zhubao.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

/** AgentRunner 消息循环单测（临时工作区 + 脚本化 stub LLM，无需网络） */
class AgentRunnerTest {

    @TempDir
    Path ws;

    private static final class StubClient implements LlmClient {
        private final Queue<List<StreamEvent>> scripts = new ArrayDeque<>();

        StubClient(List<List<StreamEvent>> scripts) {
            this.scripts.addAll(scripts);
        }

        @Override
        public LlmStream stream(ChatRequest request) {
            List<StreamEvent> script = scripts.isEmpty()
                    ? List.of(new StreamEvent.StreamEnd("end_turn", 0, 0))
                    : scripts.poll();
            return new LlmStream(new LinkedBlockingQueue<>(script), () -> { }, () -> { });
        }
    }

    private static final class StubUi implements AgentUi {
        final List<String> steps = new ArrayList<>();
        final List<ToolCall> calls = new ArrayList<>();
        final List<ToolResultRec> results = new ArrayList<>();
        PermissionChoice choice = PermissionChoice.ALLOW;

        record ToolResultRec(String name, boolean error, String output) {
        }

        @Override
        public void onStep(String status) {
            steps.add(status);
        }

        @Override
        public void onEvent(StreamEvent event) {
        }

        @Override
        public void onToolCall(ToolCall call) {
            calls.add(call);
        }

        @Override
        public void onToolResult(com.zhubao.tool.ToolResult result, int previewLines) {
            results.add(new ToolResultRec(result.name(), result.isError(), result.output()));
        }

        @Override
        public PermissionChoice askPermission(ToolCall call) {
            return choice;
        }
    }

    private List<ToolSpec> specs(ToolRegistry registry) {
        return registry.all().stream()
                .map(t -> new ToolSpec(t.name(), t.description(), t.inputSchema()))
                .toList();
    }

    private record Harness(Conversation conversation, StubUi ui, ToolRegistry registry,
                           PermissionManager permissions, SerialToolExecutor executor, List<ToolSpec> specs) {
    }

    private Harness harness() {
        ToolRegistry registry = new ToolRegistry(new PathGuard(ws), 200);
        PermissionManager permissions = new PermissionManager(registry);
        StubUi ui = new StubUi();
        SerialToolExecutor executor = new SerialToolExecutor(registry, permissions, ui, 5);
        return new Harness(new Conversation(), ui, registry, permissions, executor, specs(registry));
    }

    @Test
    void plainTextTurnEndsImmediately() {
        Harness h = harness();
        StubClient client = new StubClient(List.of(
                List.of(new StreamEvent.TextDelta("你好"), new StreamEvent.StreamEnd("end_turn", 3, 4))));
        AgentRunner.Result r = AgentRunner.run(client, h.conversation, "hi", h.specs, h.executor, h.ui, 60);
        assertFalse(r.error(), r.errorMessage());
        assertEquals("你好", r.text());
        assertFalse(r.limitReached());
        assertEquals(2, h.conversation.messageCount());
        assertEquals("hi", h.conversation.getMessages().get(0).getContent());
    }

    @Test
    void toolUseExecutesAndRefillsThenEnds() throws Exception {
        Harness h = harness();
        StubClient client = new StubClient(List.of(
                List.of(new StreamEvent.ToolCall("tu1", "write_file",
                                ToolCall.json(java.util.Map.of("path", "a.txt", "content", "hello"))),
                        new StreamEvent.StreamEnd("tool_use", 5, 6)),
                List.of(new StreamEvent.TextDelta("完成"), new StreamEvent.StreamEnd("end_turn", 7, 8))));
        AgentRunner.Result r = AgentRunner.run(client, h.conversation, "写个文件", h.specs, h.executor, h.ui, 60);
        assertFalse(r.error(), r.errorMessage());
        assertEquals("完成", r.text());
        // 真实工具在临时工作区执行
        assertEquals("hello", Files.readString(ws.resolve("a.txt")));
        // 一次性回填：user + assistant(tool_use) + user(tool_result) + assistant(完成)
        List<Message> msgs = h.conversation.getMessages();
        assertEquals(4, msgs.size());
        assertInstanceOf(ContentBlock.ToolUseBlock.class, msgs.get(1).getBlocks().get(0));
        assertInstanceOf(ContentBlock.ToolResultBlock.class, msgs.get(2).getBlocks().get(0));
        assertEquals("完成", msgs.get(3).getContent());
        // UI 收到工具摘要与结果
        assertEquals(1, h.ui.calls.size());
        assertEquals("write_file", h.ui.calls.get(0).name());
        assertEquals(1, h.ui.results.size());
        assertFalse(h.ui.results.get(0).error());
    }

    @Test
    void permissionDeniedRefillsErrorResult() throws Exception {
        Harness h = harness();
        h.ui.choice = PermissionChoice.DENY;
        StubClient client = new StubClient(List.of(
                List.of(new StreamEvent.ToolCall("tu1", "write_file",
                                ToolCall.json(java.util.Map.of("path", "a.txt", "content", "x"))),
                        new StreamEvent.StreamEnd("tool_use", 1, 1)),
                List.of(new StreamEvent.StreamEnd("end_turn", 1, 1))));
        AgentRunner.Result r = AgentRunner.run(client, h.conversation, "写文件", h.specs, h.executor, h.ui, 60);
        assertFalse(r.error(), r.errorMessage());
        ContentBlock.ToolResultBlock block = (ContentBlock.ToolResultBlock) h.conversation.getMessages().get(2).getBlocks().get(0);
        assertTrue(block.isError());
        assertTrue(block.output().contains("拒绝"));
        assertFalse(Files.exists(ws.resolve("a.txt")), "拒绝后不应产生副作用");
    }

    @Test
    void limitReachedStopsWithHint() {
        Harness h = harness();
        // 每一步都返回工具调用；上限 1 → 执行 1 次后停止
        StubClient client = new StubClient(List.of(
                List.of(new StreamEvent.ToolCall("tu1", "read_file", "{\"path\":\"x.txt\"}"),
                        new StreamEvent.StreamEnd("tool_use", 1, 1)),
                List.of(new StreamEvent.ToolCall("tu2", "read_file", "{\"path\":\"y.txt\"}"),
                        new StreamEvent.StreamEnd("tool_use", 1, 1))));
        AgentRunner.Result r = AgentRunner.run(client, h.conversation, "跑", h.specs, h.executor, h.ui, 1);
        assertTrue(r.limitReached(), "达到上限应返回 limitReached");
        assertFalse(r.error());
    }

    @Test
    void stepIdleTimeoutYieldsError() {
        Harness h = harness();
        // stub 返回空队列：该步永不产生事件 → 短空闲超时触发
        StubClient client = new StubClient(List.of(List.of()));
        AgentRunner.Result r = AgentRunner.run(client, h.conversation, "问", h.specs, h.executor, h.ui, 60, 300);
        assertTrue(r.error());
        assertTrue(r.errorMessage().contains("超时"), r.errorMessage());
    }

    @Test
    void streamErrorYieldsErrorResult() {
        Harness h = harness();
        StubClient client = new StubClient(List.of(List.of(new StreamEvent.Error("网络错误"))));
        AgentRunner.Result r = AgentRunner.run(client, h.conversation, "问", h.specs, h.executor, h.ui, 60);
        assertTrue(r.error());
        assertTrue(r.errorMessage().contains("网络错误"));
        assertEquals(1, h.conversation.messageCount(), "出错时只保留用户消息");
    }

    @Test
    void runPlanResearchesThenEndsWithPlanTextNoSideEffects() throws Exception {
        Harness h = harness();
        Files.writeString(ws.resolve("a.txt"), "hello");
        StubClient client = new StubClient(List.of(
                // 第一步：只读调研 read_file（带 StreamEnd 结束该步）
                List.of(new StreamEvent.ToolCall("tc1", "read_file", "{\"path\":\"a.txt\"}"),
                        new StreamEvent.StreamEnd("tool_use", 1, 2)),
                // 第二步：end_turn 输出计划
                List.of(new StreamEvent.TextDelta("计划：1. 修改 a.txt 内容"),
                        new StreamEvent.StreamEnd("end_turn", 5, 6))));
        PlanModeExecutor planExec = new PlanModeExecutor(h.registry, h.permissions, h.ui, 5);
        AgentRunner.Result r = AgentRunner.runPlan(client, h.conversation, "计划修改 a.txt",
                h.specs, planExec, h.ui, 60, 5_000);
        assertFalse(r.error(), r.errorMessage());
        assertTrue(r.text().contains("计划"), r.text());
        // 会话：user + assistant(tool_use) + user(tool_result) + assistant(计划)
        assertEquals(4, h.conversation.messageCount());
        // 文件未被修改（只读调研）
        assertEquals("hello", Files.readString(ws.resolve("a.txt")));
    }

    // ── M4（spec F1/F4）：本轮累计与最后一步缓存命中 ─────────────────
    @Test
    void totalsAndCacheAccumulatedAcrossSteps() throws Exception {
        Harness h = harness();
        StubClient client = new StubClient(List.of(
                List.of(new StreamEvent.ToolCall("tu1", "read_file", "{\"path\":\"a.txt\"}"),
                        new StreamEvent.StreamEnd("tool_use", 5, 6, 10, 2)),
                List.of(new StreamEvent.TextDelta("完成"), new StreamEvent.StreamEnd("end_turn", 7, 8, 30, 4))));
        AgentRunner.Result r = AgentRunner.run(client, h.conversation, "跑", h.specs, h.executor, h.ui, 60);
        assertFalse(r.error(), r.errorMessage());
        assertEquals("完成", r.text());
        assertEquals(12, r.totalInputTokens(), "本轮 input 求和");
        assertEquals(14, r.totalOutputTokens(), "本轮 output 求和");
        assertEquals(30, r.cacheReadTokens(), "最后一步缓存命中");
        assertEquals(4, r.cacheCreationTokens());
        assertFalse(r.interrupted());
    }

    // ── M4（spec F5）：生成被中断 → 半成品回滚、不写会话 ─────────────
    @Test
    void interruptedGenerationRollsBackPartialAndFlags() {
        Harness h = harness();
        StubClient client = new StubClient(List.of(
                List.of(new StreamEvent.TextDelta("半成品"), new StreamEvent.Error("已中断"))));
        AgentRunner.Result r = AgentRunner.run(client, h.conversation, "问", h.specs, h.executor, h.ui, 60);
        assertTrue(r.interrupted());
        assertTrue(r.error());
        assertEquals("已中断", r.errorMessage());
        assertEquals(1, h.conversation.messageCount(), "半成品不写入会话，仅保留用户消息");
    }


    // ── M5（spec F3.2/F3.10）：子任务执行 ─────────────────────────
    @Test
    void runSubtaskRunsInIsolatedConversationAndReturnsText() {
        Harness h = harness();
        h.conversation.addUser("子任务：读 a.txt 并总结");
        StubClient client = new StubClient(List.of(
                List.of(new StreamEvent.TextDelta("子任务完成"), new StreamEvent.StreamEnd("end_turn", 2, 3))));
        AgentRunner.Result r = AgentRunner.runSubtask(client, h.conversation, h.specs, h.executor, h.ui,
                30, AgentRunner.STEP_IDLE_TIMEOUT_MS, null);
        assertFalse(r.error(), r.errorMessage());
        assertEquals("子任务完成", r.text());
        assertEquals(2, r.inputTokens());
        // 会话：user(任务) + assistant(最终报告)
        assertEquals(2, h.conversation.messageCount());
    }

    @Test
    void runSubtaskStopsAtStepLimit() {
        Harness h = harness();
        h.conversation.addUser("子任务：循环工具");
        // 每一步都返回工具调用；子任务步数上限 1 → 执行 1 步后停止
        StubClient client = new StubClient(List.of(
                List.of(new StreamEvent.ToolCall("tu1", "read_file", "{\"path\":\"x.txt\"}"),
                        new StreamEvent.StreamEnd("tool_use", 1, 1)),
                List.of(new StreamEvent.ToolCall("tu2", "read_file", "{\"path\":\"y.txt\"}"),
                        new StreamEvent.StreamEnd("tool_use", 1, 1))));
        AgentRunner.Result r = AgentRunner.runSubtask(client, h.conversation, h.specs, h.executor, h.ui,
                1, AgentRunner.STEP_IDLE_TIMEOUT_MS, null);
        assertTrue(r.limitReached(), "达到子任务步数上限应返回 limitReached");
        assertFalse(r.error());
    }
}
