package com.zhubao.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhubao.agent.AgentUi;
import com.zhubao.llm.StreamEvent;
import com.zhubao.permission.PermissionChoice;
import com.zhubao.permission.PermissionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M5（spec F2）并行工具执行测试：
 * ① 读段并行 + 写串行 + 结果保序；② 单调用失败不拖垮段；③ Ctrl+C 中断停止剩余调用；
 * ④ UI 回调由调度线程串行（记录顺序与调用顺序一致）。
 *
 * <p>用测试专用 mock 工具（可配置 delay/fail/readOnly）注入 ToolRegistry，可观测并行与串行耗时。
 */
class ParallelToolExecutorTest {

    @TempDir
    Path ws;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 可配置的 mock 工具：delayMs 模拟耗时、fail 模拟失败、readOnly 决定归并行段还是串行段 */
    private static final class MockTool implements Tool {
        private final String name;
        private final boolean readOnly;
        private final long delayMs;
        private final boolean fail;

        MockTool(String name, boolean readOnly, long delayMs, boolean fail) {
            this.name = name;
            this.readOnly = readOnly;
            this.delayMs = delayMs;
            this.fail = fail;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "mock " + name; }
        @Override public JsonNode inputSchema() { return MAPPER.createObjectNode(); }
        @Override public boolean readOnly() { return readOnly; }

        @Override
        public ToolResult execute(ToolCall call) {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ToolResult.error(call, "interrupted");
                }
            }
            if (fail) {
                return ToolResult.error(call, "mock-fail-" + name);
            }
            return ToolResult.ok(call, name + "-ok");
        }
    }

    /** 记录 UI 回调顺序（线程安全），askPermission 默认 ALLOW */
    private static final class RecordingUi implements AgentUi {
        final List<String> toolCalls = new CopyOnWriteArrayList<>();
        final List<String> toolResults = new CopyOnWriteArrayList<>();
        PermissionChoice choice = PermissionChoice.ALLOW;

        @Override public void onStep(String status) { }
        @Override public void onEvent(StreamEvent event) { }
        @Override public void onToolCall(ToolCall call) { toolCalls.add(call.name()); }
        @Override public void onToolResult(ToolResult result, int previewLines) { toolResults.add(result.name()); }
        @Override public PermissionChoice askPermission(ToolCall call) { return choice; }
    }

    private ToolCall call(String name) {
        return new ToolCall("id-" + name, name, ToolCall.json(Map.of()));
    }

    private ParallelToolExecutor executor(RecordingUi ui, Tool... tools) {
        ToolRegistry registry = new ToolRegistry(new PathGuard(ws), 200);
        for (Tool t : tools) {
            registry.registerExternal(t);
        }
        return new ParallelToolExecutor(registry, new PermissionManager(registry), ui, 5);
    }

    @Test
    void resultsPreserveOriginalCallOrder() {
        RecordingUi ui = new RecordingUi();
        ParallelToolExecutor ex = executor(ui,
                new MockTool("read_a", true, 50, false),
                new MockTool("read_b", true, 50, false),
                new MockTool("write_c", false, 0, false),
                new MockTool("read_d", true, 50, false));

        List<ToolResult> r = ex.execute(List.of(call("read_a"), call("read_b"), call("write_c"), call("read_d")));

        assertEquals(4, r.size());
        assertEquals("read_a-ok", r.get(0).output());
        assertEquals("read_b-ok", r.get(1).output());
        assertEquals("write_c-ok", r.get(2).output());
        assertEquals("read_d-ok", r.get(3).output());
        // UI 回调顺序 == 调用顺序（调度线程串行）
        assertEquals(List.of("read_a", "read_b", "write_c", "read_d"), ui.toolCalls);
        assertEquals(List.of("read_a", "read_b", "write_c", "read_d"), ui.toolResults);
    }

    @Test
    void readSegmentRunsInParallelFasterThanSerial() {
        RecordingUi ui = new RecordingUi();
        ParallelToolExecutor ex = executor(ui,
                new MockTool("read_a", true, 200, false),
                new MockTool("read_b", true, 200, false));

        long start = System.nanoTime();
        ex.execute(List.of(call("read_a"), call("read_b")));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // 两个 200ms 读：并行应明显 < 400ms（串行下限）
        assertTrue(elapsedMs < 350, "并行读应快于串行，实际 " + elapsedMs + "ms");
    }

    @Test
    void writeToolsRunSeriallyInOriginalOrder() {
        RecordingUi ui = new RecordingUi();
        ParallelToolExecutor ex = executor(ui,
                new MockTool("write_a", false, 200, false),
                new MockTool("write_b", false, 200, false));

        long start = System.nanoTime();
        List<ToolResult> r = ex.execute(List.of(call("write_a"), call("write_b")));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(2, r.size());
        assertEquals("write_a-ok", r.get(0).output());
        assertEquals("write_b-ok", r.get(1).output());
        // 两个 200ms 写：串行应 >= 380ms（并行则 ~200ms）
        assertTrue(elapsedMs >= 380, "写工具应串行执行，实际 " + elapsedMs + "ms");
    }

    @Test
    void singleFailureDoesNotFailWholeSegment() {
        RecordingUi ui = new RecordingUi();
        ParallelToolExecutor ex = executor(ui,
                new MockTool("read_ok", true, 0, false),
                new MockTool("read_bad", true, 0, true),
                new MockTool("read_ok2", true, 0, false));

        List<ToolResult> r = ex.execute(List.of(call("read_ok"), call("read_bad"), call("read_ok2")));

        assertEquals(3, r.size());
        assertFalse(r.get(0).isError());
        assertTrue(r.get(1).isError());
        assertTrue(r.get(1).output().contains("mock-fail"));
        assertFalse(r.get(2).isError());
    }

    @Test
    void interruptStopsRemainingCalls() throws Exception {
        RecordingUi ui = new RecordingUi();
        ParallelToolExecutor ex = executor(ui,
                new MockTool("slow_write", false, 500, false),
                new MockTool("read_after", true, 0, false));

        AtomicReference<List<ToolResult>> resultRef = new AtomicReference<>();
        Thread worker = new Thread(() -> resultRef.set(ex.execute(
                List.of(call("slow_write"), call("read_after")))));
        worker.start();
        Thread.sleep(120); // slow_write 串行执行中
        worker.interrupt();
        worker.join(3_000);

        // 中断后：后续 read_after 不再执行
        List<ToolResult> r = resultRef.get();
        assertNotNull(r);
        assertEquals(1, r.size(), "中断后剩余调用不应执行");
        assertEquals("slow_write", r.get(0).name());
        assertFalse(ui.toolCalls.contains("read_after"), "read_after 不应被调度");
    }

    @Test
    void interruptDuringParallelSegmentDiscardsPendingResults() throws Exception {
        RecordingUi ui = new RecordingUi();
        ParallelToolExecutor ex = executor(ui,
                new MockTool("slow_read_a", true, 500, false),
                new MockTool("slow_read_b", true, 500, false));

        AtomicReference<List<ToolResult>> resultRef = new AtomicReference<>();
        Thread worker = new Thread(() -> resultRef.set(ex.execute(
                List.of(call("slow_read_a"), call("slow_read_b")))));
        worker.start();
        Thread.sleep(120); // 并行段执行中
        worker.interrupt();
        worker.join(3_000);

        // 并行段 get 被 interrupt：丢弃未完成结果（可能 0 个或已完成的部分，绝不含未完成的）
        List<ToolResult> r = resultRef.get();
        assertNotNull(r);
        assertTrue(r.size() < 2, "中断后未完成结果应被丢弃，实际 " + r.size());
    }
}
