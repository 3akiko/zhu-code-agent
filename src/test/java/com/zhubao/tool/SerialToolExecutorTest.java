package com.zhubao.tool;

import com.zhubao.agent.AgentUi;
import com.zhubao.history.FileHistory;
import com.zhubao.llm.StreamEvent;
import com.zhubao.permission.PermissionChoice;
import com.zhubao.permission.PermissionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SerialToolExecutor 快照钩子单测（M3 spec F3）：
 * 写成功生成检查点、写失败 discardLast、只读不触发、超大文件跳过并注明。
 */
class SerialToolExecutorTest {

    @TempDir
    Path ws;

    private static final class Ui implements AgentUi {
        PermissionChoice choice = PermissionChoice.ALLOW;

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
        }

        @Override
        public PermissionChoice askPermission(ToolCall call) {
            return choice;
        }
    }

    private ToolCall call(String name, Map<String, Object> args) {
        return new ToolCall("c1", name, ToolCall.json(args));
    }

    private SerialToolExecutor executor(FileHistory history, String sessionId) {
        ToolRegistry registry = new ToolRegistry(new PathGuard(ws), 200);
        PermissionManager pm = new PermissionManager(registry);
        return new SerialToolExecutor(registry, pm, new Ui(), 5, history, sessionId);
    }

    @Test
    void writeSuccessCreatesCheckpoint() throws Exception {
        Files.writeString(ws.resolve("a.txt"), "before");
        FileHistory history = new FileHistory(ws.resolve("snap"), new PathGuard(ws));
        SerialToolExecutor ex = executor(history, "s1");
        List<ToolResult> r = ex.execute(List.of(call("write_file", Map.of("path", "a.txt", "content", "after"))));
        assertFalse(r.get(0).isError(), r.get(0).output());
        assertEquals("after", Files.readString(ws.resolve("a.txt")));
        var list = history.list("s1");
        assertEquals(1, list.size());
        assertEquals("before", list.get(0).beforeContent());
    }

    @Test
    void editFailureDiscardsCheckpoint() throws Exception {
        Files.writeString(ws.resolve("a.txt"), "hello");
        FileHistory history = new FileHistory(ws.resolve("snap"), new PathGuard(ws));
        SerialToolExecutor ex = executor(history, "s1");
        List<ToolResult> r = ex.execute(List.of(call("edit_file",
                Map.of("path", "a.txt", "old_string", "zzz", "new_string", "x"))));
        assertTrue(r.get(0).isError());
        assertEquals("hello", Files.readString(ws.resolve("a.txt")));
        assertTrue(history.list("s1").isEmpty(), "失败写不应残留检查点");
    }

    @Test
    void readOnlyDoesNotCreateCheckpoint() throws Exception {
        Files.writeString(ws.resolve("a.txt"), "hello");
        FileHistory history = new FileHistory(ws.resolve("snap"), new PathGuard(ws));
        SerialToolExecutor ex = executor(history, "s1");
        ex.execute(List.of(call("read_file", Map.of("path", "a.txt"))));
        assertTrue(history.list("s1").isEmpty());
    }

    @Test
    void oversizeExistingFileSkippedAndAnnotated() throws Exception {
        byte[] big = new byte[11 * 1024 * 1024];
        java.util.Arrays.fill(big, (byte) 'x');
        Files.write(ws.resolve("big.txt"), big);
        FileHistory history = new FileHistory(ws.resolve("snap"), new PathGuard(ws));
        SerialToolExecutor ex = executor(history, "s1");
        List<ToolResult> r = ex.execute(List.of(call("write_file", Map.of("path", "big.txt", "content", "small"))));
        assertFalse(r.get(0).isError(), r.get(0).output());
        assertTrue(r.get(0).output().contains("未参与快照/回滚"), r.get(0).output());
        assertTrue(history.list("s1").isEmpty());
        assertEquals("small", Files.readString(ws.resolve("big.txt")));
    }

    @Test
    void noHistoryNoSnapshotStillWorks() throws Exception {
        ToolRegistry registry = new ToolRegistry(new PathGuard(ws), 200);
        PermissionManager pm = new PermissionManager(registry);
        SerialToolExecutor ex = new SerialToolExecutor(registry, pm, new Ui(), 5);
        List<ToolResult> r = ex.execute(List.of(call("write_file", Map.of("path", "a.txt", "content", "x"))));
        assertFalse(r.get(0).isError(), r.get(0).output());
        assertEquals("x", Files.readString(ws.resolve("a.txt")));
    }
}
