package com.zhubao.tool;

import com.zhubao.agent.AgentUi;
import com.zhubao.llm.ChatRequest;
import com.zhubao.llm.LlmClient;
import com.zhubao.llm.LlmStream;
import com.zhubao.llm.StreamEvent;
import com.zhubao.permission.PermissionChoice;
import com.zhubao.permission.PermissionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M5（spec F3）task 工具级测试：缺参/深度/并发护栏、子任务执行与摘要、失败摘要、
 * 工具池裁剪（深度封顶不含 task）。
 */
class TaskToolTest {

    @TempDir
    Path ws;

    /** 脚本化 stub LLM（同 AgentRunnerTest） */
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

    /** 记录子任务 UI 钩子；askPermission 默认 ALLOW */
    private static final class RecordingUi implements AgentUi {
        final List<String> subtaskStarts = new ArrayList<>();
        final List<String> subtaskEnds = new ArrayList<>();
        final List<Integer> permissionSources = new ArrayList<>();
        int askPermissionCount;

        @Override public void onStep(String status) { }
        @Override public void onEvent(StreamEvent event) { }
        @Override public void onToolCall(ToolCall call) { }
        @Override public void onToolResult(ToolResult result, int previewLines) { }
        @Override public PermissionChoice askPermission(ToolCall call) { askPermissionCount++; return PermissionChoice.ALLOW; }
        @Override public void onSubtaskStart(int id, String promptPreview) { subtaskStarts.add(id + ":" + promptPreview); }
        @Override public void onSubtaskEnd(int id, String summary) { subtaskEnds.add(id + ":" + summary); }
        @Override public void onSubtaskPermission(int id, ToolCall call) { permissionSources.add(id); }
    }

    private ToolCall taskCall(String prompt) {
        return new ToolCall("tu1", "task", ToolCall.json(Map.of("prompt", prompt)));
    }

    private TaskTool taskTool(RecordingUi ui, int maxDepth, int maxParallel, List<List<StreamEvent>> scripts) {
        ToolRegistry registry = new ToolRegistry(new PathGuard(ws), 200);
        TaskTool task = new TaskTool();
        task.setClientProvider(() -> new StubClient(scripts));
        task.setRegistry(registry);
        task.setPermissions(new PermissionManager(registry));
        task.setUi(ui);
        task.setMaxDepth(maxDepth);
        task.setMaxParallel(maxParallel);
        task.setMaxSteps(30);
        task.setPreviewLines(5);
        return task;
    }

    @Test
    void missingPromptRejected() {
        RecordingUi ui = new RecordingUi();
        TaskTool task = taskTool(ui, 2, 4, List.of());
        ToolResult r = task.execute(new ToolCall("tu1", "task", "{}"));
        assertTrue(r.isError());
        assertTrue(r.output().contains("prompt 必填"), r.output());
    }

    @Test
    void depthLimitRejectedWithReadableError() {
        RecordingUi ui = new RecordingUi();
        TaskTool task = taskTool(ui, 0, 4, List.of());
        ToolResult r = task.execute(taskCall("调研"));
        assertTrue(r.isError());
        assertTrue(r.output().contains("嵌套深度超限"), r.output());
    }

    @Test
    void parallelLimitRejectedWithReadableError() {
        RecordingUi ui = new RecordingUi();
        TaskTool task = taskTool(ui, 2, 0, List.of());
        ToolResult r = task.execute(taskCall("调研"));
        assertTrue(r.isError());
        assertTrue(r.output().contains("并行子任务超限"), r.output());
    }

    @Test
    void subtaskRunsAndReturnsStructuredSummary() {
        RecordingUi ui = new RecordingUi();
        TaskTool task = taskTool(ui, 2, 4, List.of(
                List.of(new StreamEvent.TextDelta("子任务报告"), new StreamEvent.StreamEnd("end_turn", 7, 8))));

        ToolResult r = task.execute(taskCall("调研并报告"));

        assertFalse(r.isError(), r.output());
        assertTrue(r.output().contains("[task#1] 状态=完成"), r.output());
        assertTrue(r.output().contains("子任务报告"), r.output());
        assertTrue(r.output().contains("in 7/out 8"), r.output());
        assertEquals(1, ui.subtaskStarts.size());
        assertEquals(1, ui.subtaskEnds.size());
        assertTrue(ui.subtaskStarts.get(0).startsWith("1:"), ui.subtaskStarts.get(0));
    }

    @Test
    void subtaskFailureReturnsErrorSummaryNoAutoRetry() {
        RecordingUi ui = new RecordingUi();
        // 子任务 LLM 直接返回错误：不自动重试，isError 摘要回填
        TaskTool task = taskTool(ui, 2, 4, List.of(
                List.of(new StreamEvent.Error("网络错误"))));

        ToolResult r = task.execute(taskCall("调研"));

        assertTrue(r.isError());
        assertTrue(r.output().contains("状态=失败"), r.output());
        assertTrue(r.output().contains("网络错误"), r.output());
        // 只调用了一次 stub（无重试）
    }

    @Test
    void buildSubtaskToolsPrunesTaskAtDepthLimit() {
        RecordingUi ui = new RecordingUi();
        ToolRegistry registry = new ToolRegistry(new PathGuard(ws), 200);
        TaskTool task = new TaskTool();
        task.setRegistry(registry);
        task.setMaxDepth(1); // 父(0) → 子(1) 封顶：childDepth=1 时不含 task
        registry.registerExternal(task);

        var toolsAtDepth1 = task.buildSubtaskTools(1);
        assertTrue(toolsAtDepth1.stream().noneMatch(t -> "task".equals(t.name())),
                "深度封顶的子 agent 工具池不应含 task");
        var toolsAtDepth0 = task.buildSubtaskTools(0);
        assertTrue(toolsAtDepth0.stream().anyMatch(t -> "task".equals(t.name())),
                "未封顶的工具池应含 task");
    }
}
