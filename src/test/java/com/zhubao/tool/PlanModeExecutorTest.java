package com.zhubao.tool;

import com.zhubao.agent.AgentUi;
import com.zhubao.llm.StreamEvent;
import com.zhubao.permission.PermissionChoice;
import com.zhubao.permission.PermissionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PlanModeExecutor 单测（M3 spec F2）：写/bash 拦截回填错误、只读放行、无副作用、UI 回调。
 */
class PlanModeExecutorTest {

    @TempDir
    Path ws;

    private static final class Ui implements AgentUi {
        final List<String> calls = new ArrayList<>();
        final List<Boolean> results = new ArrayList<>();

        @Override
        public void onStep(String status) {
        }

        @Override
        public void onEvent(StreamEvent event) {
        }

        @Override
        public void onToolCall(ToolCall call) {
            calls.add(call.name());
        }

        @Override
        public void onToolResult(ToolResult result, int previewLines) {
            results.add(result.isError());
        }

        @Override
        public PermissionChoice askPermission(ToolCall call) {
            return PermissionChoice.ALLOW;
        }
    }

    private PlanModeExecutor newExecutor(Ui ui) {
        ToolRegistry registry = new ToolRegistry(new PathGuard(ws), 200);
        PermissionManager pm = new PermissionManager(registry);
        return new PlanModeExecutor(registry, pm, ui, 5);
    }

    private ToolCall call(String name, Map<String, Object> args) {
        return new ToolCall("c1", name, ToolCall.json(args));
    }

    @Test
    void writeEditBashBlockedWithReadableErrorAndNoSideEffect() throws Exception {
        Ui ui = new Ui();
        PlanModeExecutor ex = newExecutor(ui);
        List<ToolResult> r = ex.execute(List.of(
                call("write_file", Map.of("path", "a.txt", "content", "x")),
                call("edit_file", Map.of("path", "a.txt", "old_string", "a", "new_string", "b")),
                call("bash", Map.of("command", "touch b.txt"))));
        assertEquals(3, r.size());
        for (ToolResult result : r) {
            assertTrue(result.isError());
            assertTrue(result.output().contains("计划阶段禁止该操作"), result.output());
        }
        // 零副作用：没有文件被创建/修改
        assertFalse(Files.exists(ws.resolve("a.txt")));
        assertFalse(Files.exists(ws.resolve("b.txt")));
        assertEquals(List.of("write_file", "edit_file", "bash"), ui.calls);
        assertEquals(3, ui.results.size());
    }

    @Test
    void readOnlyToolsDelegatedAndExecuted() throws Exception {
        Files.writeString(ws.resolve("x.txt"), "hello");
        Ui ui = new Ui();
        PlanModeExecutor ex = newExecutor(ui);
        List<ToolResult> r = ex.execute(List.of(call("read_file", Map.of("path", "x.txt"))));
        assertEquals(1, r.size());
        assertFalse(r.get(0).isError(), r.get(0).output());
        assertEquals("hello\n", r.get(0).output());
        assertEquals(List.of("read_file"), ui.calls);
    }
}
