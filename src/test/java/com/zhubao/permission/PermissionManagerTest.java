package com.zhubao.permission;

import com.zhubao.tool.PathGuard;
import com.zhubao.tool.ToolCall;
import com.zhubao.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PermissionManagerTest {

    @TempDir
    Path ws;

    private PermissionManager manager() {
        return new PermissionManager(new ToolRegistry(new PathGuard(ws)));
    }

    private ToolCall call(String name, Map<String, Object> args) {
        return new ToolCall("c1", name, ToolCall.json(args));
    }

    @Test
    void readOnlyToolsAutoAllow() {
        PermissionManager pm = manager();
        assertEquals(PermissionDecision.ALLOW, pm.decide(call("read_file", Map.of("path", "a.txt"))));
        assertEquals(PermissionDecision.ALLOW, pm.decide(call("grep", Map.of("pattern", "x"))));
        assertEquals(PermissionDecision.ALLOW, pm.decide(call("glob", Map.of("pattern", "*.java"))));
    }

    @Test
    void writeToolsNeedConfirm() {
        PermissionManager pm = manager();
        assertEquals(PermissionDecision.NEED_CONFIRM, pm.decide(call("write_file", Map.of("path", "a.txt", "content", "x"))));
        assertEquals(PermissionDecision.NEED_CONFIRM, pm.decide(call("edit_file", Map.of("path", "a.txt", "old_string", "a", "new_string", "b"))));
        assertEquals(PermissionDecision.NEED_CONFIRM, pm.decide(call("bash", Map.of("command", "mvn test"))));
    }

    @Test
    void fileWriteRememberedByPathPerSpec() {
        PermissionManager pm = manager();
        ToolCall writeA = call("write_file", Map.of("path", "a.txt", "content", "x"));
        pm.rememberAlways(writeA);
        // spec F3：文件写按路径记忆 → 同路径（不同 content/参数顺序）应命中
        assertEquals(PermissionDecision.ALLOW, pm.decide(writeA));
        assertEquals(PermissionDecision.ALLOW, pm.decide(call("write_file", Map.of("content", "y", "path", "a.txt"))));
        // 不同路径仍需确认
        assertEquals(PermissionDecision.NEED_CONFIRM, pm.decide(call("write_file", Map.of("path", "b.txt", "content", "x"))));
    }

    @Test
    void bashRememberedByExactCommand() {
        PermissionManager pm = manager();
        ToolCall mvnTest = call("bash", Map.of("command", "mvn test"));
        pm.rememberAlways(mvnTest);
        assertEquals(PermissionDecision.ALLOW, pm.decide(mvnTest));
        assertEquals(PermissionDecision.NEED_CONFIRM, pm.decide(call("bash", Map.of("command", "mvn package"))));
    }

    @Test
    void dangerousCommandForcesConfirmEvenIfRemembered() {
        PermissionManager pm = manager();
        ToolCall rm = call("bash", Map.of("command", "rm -rf ./build"));
        pm.rememberAlways(rm);
        assertEquals(PermissionDecision.NEED_CONFIRM, pm.decide(rm), "危险命令即使曾总是允许也强制确认");
    }

    @Test
    void resetClearsMemory() {
        PermissionManager pm = manager();
        ToolCall write = call("write_file", Map.of("path", "a.txt", "content", "x"));
        pm.rememberAlways(write);
        assertEquals(1, pm.allowedList().size());
        pm.reset();
        assertEquals(0, pm.allowedList().size());
        assertEquals(PermissionDecision.NEED_CONFIRM, pm.decide(write));
    }
}
