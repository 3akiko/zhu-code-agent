package com.zhubao.integration;

import com.zhubao.agent.AgentRunner;
import com.zhubao.agent.AgentUi;
import com.zhubao.conversation.Conversation;
import com.zhubao.history.FileHistory;
import com.zhubao.llm.ChatRequest;
import com.zhubao.llm.LlmClient;
import com.zhubao.llm.StreamEvent;
import com.zhubao.llm.ToolSpec;
import com.zhubao.permission.PermissionChoice;
import com.zhubao.permission.PermissionManager;
import com.zhubao.permission.PermissionMode;
import com.zhubao.tool.PathGuard;
import com.zhubao.tool.PlanModeExecutor;
import com.zhubao.tool.SerialToolExecutor;
import com.zhubao.tool.ToolCall;
import com.zhubao.tool.ToolRegistry;
import com.zhubao.tool.ToolResult;
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

/**
 * M3 mock LLM 端到端（checklist 场景 1/2/3/5）：
 * 脚本化 LLM + 真实工具（临时工作区）验证 /plan 批准与拒绝、权限模式、undo/rewind 真实回滚。
 */
class PlanModeIntegrationTest {

    @TempDir
    Path ws;

    private static final class ScriptedClient implements LlmClient {
        private final Queue<List<StreamEvent>> scripts = new ArrayDeque<>();

        ScriptedClient(List<List<StreamEvent>> scripts) {
            this.scripts.addAll(scripts);
        }

        @Override
        public BlockingQueue<StreamEvent> stream(ChatRequest request) {
            List<StreamEvent> script = scripts.isEmpty()
                    ? List.of(new StreamEvent.StreamEnd("end_turn", 0, 0))
                    : scripts.poll();
            return new LinkedBlockingQueue<>(script);
        }
    }

    private static final class Ui implements AgentUi {
        PermissionChoice choice = PermissionChoice.ALLOW;
        int askCount = 0;
        final List<String> blocked = new ArrayList<>();

        @Override
        public void onStep(String status) {
        }

        @Override
        public void onEvent(StreamEvent event) {
        }

        @Override
        public void onToolCall(ToolCall call) {
        }

        @Override
        public void onToolResult(ToolResult result, int previewLines) {
            if (result.isError() && result.output().contains("计划阶段禁止")) {
                blocked.add(result.name());
            }
        }

        @Override
        public PermissionChoice askPermission(ToolCall call) {
            askCount++;
            return choice;
        }
    }

    private ToolCall call(String name, String argumentsJson) {
        return new ToolCall("c1", name, argumentsJson);
    }

    private List<ToolSpec> specs(ToolRegistry registry) {
        return registry.all().stream()
                .map(t -> new ToolSpec(t.name(), t.description(), t.inputSchema()))
                .toList();
    }

    // ── 场景 1：/plan 批准全流程（调研 → 写被拦 → 计划 → 批准执行） ──────────

    @Test
    void planApproveResearchesThenExecutesWrite() throws Exception {
        Files.writeString(ws.resolve("a.txt"), "hello");
        ToolRegistry registry = new ToolRegistry(new PathGuard(ws), 200);
        PermissionManager pm = new PermissionManager(registry);
        Ui ui = new Ui();
        Conversation conv = new Conversation();
        List<ToolSpec> specs = specs(registry);

        // 计划阶段：read_file 调研 → 尝试 write（应被拦截）→ end_turn 出计划
        ScriptedClient planClient = new ScriptedClient(List.of(
                List.of(new StreamEvent.ToolCall("tc1", "read_file", "{\"path\":\"a.txt\"}"),
                        new StreamEvent.StreamEnd("tool_use", 1, 2)),
                List.of(new StreamEvent.ToolCall("tc2", "write_file", "{\"path\":\"a.txt\",\"content\":\"world\"}"),
                        new StreamEvent.StreamEnd("tool_use", 3, 4)),
                List.of(new StreamEvent.TextDelta("计划：1. 把 a.txt 改为 world"),
                        new StreamEvent.StreamEnd("end_turn", 5, 6))));
        PlanModeExecutor planExec = new PlanModeExecutor(registry, pm, ui, 5);
        AgentRunner.Result plan = AgentRunner.runPlan(planClient, conv, "把 a.txt 改成 world",
                specs, planExec, ui, 60, 5_000);
        assertFalse(plan.error(), plan.errorMessage());
        assertTrue(plan.text().contains("计划"), plan.text());
        // 写被拦截、零副作用
        assertEquals(List.of("write_file"), ui.blocked);
        assertEquals("hello", Files.readString(ws.resolve("a.txt")));
        assertFalse(Files.exists(ws.resolve("a.txt.tmp")));

        // 批准后执行阶段（y 路径）：write_file 真实执行
        FileHistory history = new FileHistory(ws.resolve("snap"), new PathGuard(ws));
        SerialToolExecutor exec = new SerialToolExecutor(registry, pm, ui, 5, history, "s1");
        ScriptedClient execClient = new ScriptedClient(List.of(
                List.of(new StreamEvent.ToolCall("tc3", "write_file", "{\"path\":\"a.txt\",\"content\":\"world\"}"),
                        new StreamEvent.StreamEnd("tool_use", 7, 8)),
                List.of(new StreamEvent.TextDelta("完成"), new StreamEvent.StreamEnd("end_turn", 9, 10))));
        AgentRunner.Result done = AgentRunner.runExecution(execClient, conv, specs, exec, ui, 60, 5_000);
        assertFalse(done.error(), done.errorMessage());
        assertEquals("world", Files.readString(ws.resolve("a.txt")));
        // 快照检查点已生成（批准执行阶段的写）
        assertEquals(1, history.list("s1").size());
        assertEquals("hello", history.list("s1").get(0).beforeContent());
    }

    // ── 场景 2：/plan 拒绝 → 无任何写副作用 ───────────────────────────────

    @Test
    void planDeniedHasNoSideEffects() throws Exception {
        ToolRegistry registry = new ToolRegistry(new PathGuard(ws), 200);
        PermissionManager pm = new PermissionManager(registry);
        Ui ui = new Ui();
        Conversation conv = new Conversation();

        ScriptedClient client = new ScriptedClient(List.of(
                List.of(new StreamEvent.ToolCall("tc1", "write_file", "{\"path\":\"new.txt\",\"content\":\"x\"}"),
                        new StreamEvent.StreamEnd("tool_use", 1, 1)),
                List.of(new StreamEvent.TextDelta("计划：创建 new.txt"),
                        new StreamEvent.StreamEnd("end_turn", 2, 2))));
        PlanModeExecutor planExec = new PlanModeExecutor(registry, pm, ui, 5);
        AgentRunner.Result plan = AgentRunner.runPlan(client, conv, "创建 new.txt", specs(registry),
                planExec, ui, 60, 5_000);
        assertFalse(plan.error(), plan.errorMessage());
        // d 路径 = 不执行 runExecution → 工作区无任何写副作用
        assertFalse(Files.exists(ws.resolve("new.txt")));
        assertEquals(List.of("write_file"), ui.blocked);
    }

    // ── 场景 3：权限模式（acceptEdits / bypass） ─────────────────────────

    @Test
    void acceptEditsSkipsWriteConfirmBashStillAsks() throws Exception {
        ToolRegistry registry = new ToolRegistry(new PathGuard(ws), 200);
        PermissionManager pm = new PermissionManager(registry);
        pm.setMode(PermissionMode.ACCEPT_EDITS);
        Ui ui = new Ui();
        FileHistory history = new FileHistory(ws.resolve("snap"), new PathGuard(ws));
        SerialToolExecutor ex = new SerialToolExecutor(registry, pm, ui, 5, history, "s1");

        List<ToolResult> r = ex.execute(List.of(
                call("write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}"),
                call("bash", "{\"command\":\"echo hi\"}")));
        assertFalse(r.get(0).isError(), r.get(0).output());
        assertEquals("x", Files.readString(ws.resolve("a.txt")));
        // bash 仍需确认：askCount 只应来自 bash 那次
        assertEquals(1, ui.askCount);
    }

    @Test
    void bypassAutoApprovesNonDangerousBashDangerousStillConfirms() throws Exception {
        ToolRegistry registry = new ToolRegistry(new PathGuard(ws), 200);
        PermissionManager pm = new PermissionManager(registry);
        pm.setMode(PermissionMode.BYPASS_PERMISSIONS);
        Ui ui = new Ui();
        ui.choice = PermissionChoice.DENY; // 若被询问则拒绝（证明危险命令确实被询问）
        SerialToolExecutor ex = new SerialToolExecutor(registry, pm, ui, 5);

        List<ToolResult> r = ex.execute(List.of(
                call("bash", "{\"command\":\"echo hi\"}"),
                call("bash", "{\"command\":\"rm -rf /\"}")));
        // 非危险 bash 自动执行成功
        assertFalse(r.get(0).isError(), r.get(0).output());
        // 危险命令被询问并拒绝（不执行）
        assertTrue(r.get(1).isError());
        assertTrue(r.get(1).output().contains("拒绝"), r.get(1).output());
        assertEquals(1, ui.askCount);
    }

    // ── 场景 4：undo/rewind 真实回滚（跨会话） ───────────────────────────

    @Test
    void undoRewindRestoreRealFilesAcrossSessions() throws Exception {
        Files.writeString(ws.resolve("a.txt"), "v0");
        ToolRegistry registry = new ToolRegistry(new PathGuard(ws), 200);
        PermissionManager pm = new PermissionManager(registry);
        Ui ui = new Ui();
        FileHistory history = new FileHistory(ws.resolve("snap"), new PathGuard(ws));
        SerialToolExecutor ex = new SerialToolExecutor(registry, pm, ui, 5, history, "s1");

        ex.execute(List.of(call("write_file", "{\"path\":\"a.txt\",\"content\":\"v1\"}")));
        ex.execute(List.of(call("write_file", "{\"path\":\"a.txt\",\"content\":\"v2\"}")));
        assertEquals("v2", Files.readString(ws.resolve("a.txt")));

        // 回退到检查点 0 → 回到 v0，其后检查点丢弃
        var r = history.rewindTo("s1", 0);
        assertTrue(r.ok(), r.message());
        assertEquals("v0", Files.readString(ws.resolve("a.txt")));
        assertTrue(history.list("s1").isEmpty());

        // 再次写 v3 → 生成新检查点；跨会话（新 FileHistory 实例）undo 仍可撤销
        ex.execute(List.of(call("write_file", "{\"path\":\"a.txt\",\"content\":\"v3\"}")));
        FileHistory history2 = new FileHistory(ws.resolve("snap"), new PathGuard(ws));
        assertEquals("v3", Files.readString(ws.resolve("a.txt")));
        var u = history2.undo("s1");
        assertTrue(u.ok(), u.message());
        assertEquals("v0", Files.readString(ws.resolve("a.txt")));
    }
}
