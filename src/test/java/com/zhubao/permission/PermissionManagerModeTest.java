package com.zhubao.permission;

import com.zhubao.tool.PathGuard;
import com.zhubao.tool.ToolCall;
import com.zhubao.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 权限模式单测（M3 spec F4）：三档模式行为、危险命令强制确认、reset 不影响模式。
 */
class PermissionManagerModeTest {

    @TempDir
    Path ws;

    private PermissionManager manager() {
        return new PermissionManager(new ToolRegistry(new PathGuard(ws), 200));
    }

    private ToolCall call(String name, Map<String, Object> args) {
        return new ToolCall("c1", name, ToolCall.json(args));
    }

    @Test
    void defaultModeIsNormal() {
        assertEquals(PermissionMode.NORMAL, manager().mode());
    }

    @Test
    void acceptEditsAutoApprovesWritesBashStillConfirmed() {
        PermissionManager pm = manager();
        pm.setMode(PermissionMode.ACCEPT_EDITS);
        assertEquals(PermissionDecision.ALLOW, pm.decide(call("write_file", Map.of("path", "a.txt", "content", "x"))));
        assertEquals(PermissionDecision.ALLOW, pm.decide(call("edit_file", Map.of("path", "a.txt", "old_string", "a", "new_string", "b"))));
        assertEquals(PermissionDecision.NEED_CONFIRM, pm.decide(call("bash", Map.of("command", "mvn test"))));
    }

    @Test
    void bypassPermissionsAutoApprovesNonDangerousBash() {
        PermissionManager pm = manager();
        pm.setMode(PermissionMode.BYPASS_PERMISSIONS);
        assertEquals(PermissionDecision.ALLOW, pm.decide(call("write_file", Map.of("path", "a.txt", "content", "x"))));
        assertEquals(PermissionDecision.ALLOW, pm.decide(call("bash", Map.of("command", "echo hi"))));
    }

    @Test
    void dangerousCommandStillNeedsConfirmEvenInBypass() {
        PermissionManager pm = manager();
        pm.setMode(PermissionMode.BYPASS_PERMISSIONS);
        // 危险命令（rm -rf）即使 bypass 也强制确认（spec F4 红线）
        assertEquals(PermissionDecision.NEED_CONFIRM, pm.decide(call("bash", Map.of("command", "rm -rf /"))));
        // 即使曾被「总是允许」也强制确认
        ToolCall dangerous = call("bash", Map.of("command", "rm -rf /"));
        pm.rememberAlways(dangerous);
        assertEquals(PermissionDecision.NEED_CONFIRM, pm.decide(dangerous));
    }

    @Test
    void readOnlyAlwaysAllowedRegardlessOfMode() {
        PermissionManager pm = manager();
        pm.setMode(PermissionMode.BYPASS_PERMISSIONS);
        assertEquals(PermissionDecision.ALLOW, pm.decide(call("read_file", Map.of("path", "a.txt"))));
        pm.setMode(PermissionMode.ACCEPT_EDITS);
        assertEquals(PermissionDecision.ALLOW, pm.decide(call("grep", Map.of("pattern", "x"))));
    }

    @Test
    void resetClearsAlwaysAllowedButKeepsMode() {
        PermissionManager pm = manager();
        pm.setMode(PermissionMode.ACCEPT_EDITS);
        ToolCall writeA = call("write_file", Map.of("path", "a.txt", "content", "x"));
        pm.rememberAlways(writeA);
        assertFalse(pm.allowedList().isEmpty());
        pm.reset();
        assertTrue(pm.allowedList().isEmpty());
        assertEquals(PermissionMode.ACCEPT_EDITS, pm.mode());
        // 模式仍生效：写文件自动批准
        assertEquals(PermissionDecision.ALLOW, pm.decide(writeA));
    }

    @Test
    void nullModeFallsBackToNormal() {
        PermissionManager pm = manager();
        pm.setMode(null);
        assertEquals(PermissionMode.NORMAL, pm.mode());
    }
}
