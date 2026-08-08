package com.zhubao.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DangerGuardTest {

    @TempDir
    Path ws;

    @Test
    void detectsRmRecursiveForceVariants() {
        assertTrue(DangerGuard.isDangerous("rm -rf /tmp/x"));
        assertTrue(DangerGuard.isDangerous("rm -fr foo"));
        assertTrue(DangerGuard.isDangerous("rm -r -f foo"));
        assertTrue(DangerGuard.isDangerous("rm -rfv foo"));
    }

    @Test
    void plainRmNotDangerous() {
        assertFalse(DangerGuard.isDangerous("rm foo.txt"));
        assertFalse(DangerGuard.isDangerous("rm -i foo.txt"));
        assertFalse(DangerGuard.isDangerous("echo hello"));
    }

    @Test
    void destructivePrefixesDetected() {
        assertTrue(DangerGuard.isDangerous("sudo rm -rf /"));
        assertTrue(DangerGuard.isDangerous("mkfs.ext4 /dev/sda1"));
        assertTrue(DangerGuard.isDangerous("dd if=/dev/zero of=/dev/sda"));
        assertTrue(DangerGuard.isDangerous("shutdown -h now"));
    }

    @Test
    void rmTargetsInsideWorkspaceAllowed() {
        PathGuard guard = new PathGuard(ws);
        assertDoesNotThrow(() -> DangerGuard.assertFileMutationsInWorkspace("rm -rf ./tmp", guard));
        assertDoesNotThrow(() -> DangerGuard.assertFileMutationsInWorkspace("rm -rf *.tmp", guard));
        assertDoesNotThrow(() -> DangerGuard.assertFileMutationsInWorkspace("rm -rf build/ out/", guard));
    }

    @Test
    void rmTargetsOutsideWorkspaceRejected() {
        PathGuard guard = new PathGuard(ws);
        assertThrows(ToolException.class, () -> DangerGuard.assertFileMutationsInWorkspace("rm -rf /tmp/x", guard));
        assertThrows(ToolException.class, () -> DangerGuard.assertFileMutationsInWorkspace("rm -rf ../x", guard));
        assertThrows(ToolException.class, () -> DangerGuard.assertFileMutationsInWorkspace("rm -rf /home/user/x", guard));
        assertThrows(ToolException.class, () -> DangerGuard.assertFileMutationsInWorkspace("rm -rf ../*", guard));
    }

    /** 安全红线（回归 P1）：shell 展开字符无法静态校验，必须拒绝，防止展开到 cwd 外删除 */
    @Test
    void rmTargetsWithShellExpansionRejected() {
        PathGuard guard = new PathGuard(ws);
        assertThrows(ToolException.class, () -> DangerGuard.assertFileMutationsInWorkspace("rm -rf ~/evil", guard));
        assertThrows(ToolException.class, () -> DangerGuard.assertFileMutationsInWorkspace("rm -rf $HOME/x", guard));
        assertThrows(ToolException.class, () -> DangerGuard.assertFileMutationsInWorkspace("rm -rf `pwd`/x", guard));
        assertThrows(ToolException.class, () -> DangerGuard.assertFileMutationsInWorkspace("rm -rf $(pwd)/x", guard));
        assertThrows(ToolException.class, () -> DangerGuard.assertFileMutationsInWorkspace("rm -rf ~/tmp/*", guard));
    }

    // ── 用户安全红线扩展：所有 rm / rmdir / mv / cp 的目标都必须位于工作区内 ──

    @Test
    void rmWithoutRfAlsoRestrictedToWorkspace() {
        PathGuard guard = new PathGuard(ws);
        // 不带 -rf 的 rm 同样受工作区限制（对标 Claude Code/Codex 的 delete 边界）
        assertThrows(ToolException.class, () -> DangerGuard.assertFileMutationsInWorkspace("rm /tmp/outside.txt", guard));
        assertThrows(ToolException.class, () -> DangerGuard.assertFileMutationsInWorkspace("rm -f /etc/hosts", guard));
        assertThrows(ToolException.class, () -> DangerGuard.assertFileMutationsInWorkspace("rm -r /tmp/dir", guard));
        assertThrows(ToolException.class, () -> DangerGuard.assertFileMutationsInWorkspace("rmdir /tmp/dir", guard));
        // 工作区内仍放行
        assertDoesNotThrow(() -> DangerGuard.assertFileMutationsInWorkspace("rm ./old.txt", guard));
        assertDoesNotThrow(() -> DangerGuard.assertFileMutationsInWorkspace("rm -f build/out.tmp", guard));
    }

    @Test
    void mvTargetMustBeInWorkspace() {
        PathGuard guard = new PathGuard(ws);
        assertThrows(ToolException.class, () -> DangerGuard.assertFileMutationsInWorkspace("mv a.txt /tmp/x", guard));
        assertThrows(ToolException.class, () -> DangerGuard.assertFileMutationsInWorkspace("mv -f a.txt /etc/x", guard));
        assertThrows(ToolException.class, () -> DangerGuard.assertFileMutationsInWorkspace("mv a.txt b.txt /tmp/dir", guard));
        assertThrows(ToolException.class, () -> DangerGuard.assertFileMutationsInWorkspace("mv -t /tmp/x a.txt", guard));
        assertThrows(ToolException.class, () -> DangerGuard.assertFileMutationsInWorkspace("mv --target-directory=/tmp/x a.txt", guard));
        assertThrows(ToolException.class, () -> DangerGuard.assertFileMutationsInWorkspace("mv a.txt ~/dest", guard));
        assertDoesNotThrow(() -> DangerGuard.assertFileMutationsInWorkspace("mv a.txt b.txt", guard));
        assertDoesNotThrow(() -> DangerGuard.assertFileMutationsInWorkspace("mv -f a.txt ./dir/b.txt", guard));
    }

    @Test
    void cpTargetMustBeInWorkspace() {
        PathGuard guard = new PathGuard(ws);
        assertThrows(ToolException.class, () -> DangerGuard.assertFileMutationsInWorkspace("cp a.txt /tmp/x", guard));
        assertThrows(ToolException.class, () -> DangerGuard.assertFileMutationsInWorkspace("cp -r dir /etc/x", guard));
        assertThrows(ToolException.class, () -> DangerGuard.assertFileMutationsInWorkspace("cp a.txt $HOME/x", guard));
        assertDoesNotThrow(() -> DangerGuard.assertFileMutationsInWorkspace("cp a.txt ./b.txt", guard));
        assertDoesNotThrow(() -> DangerGuard.assertFileMutationsInWorkspace("cp -r src/ dst/", guard));
    }

    @Test
    void nonMutatingCommandsNotChecked() {
        PathGuard guard = new PathGuard(ws);
        assertDoesNotThrow(() -> DangerGuard.assertFileMutationsInWorkspace("echo hello", guard));
        assertDoesNotThrow(() -> DangerGuard.assertFileMutationsInWorkspace("cat /tmp/x", guard));
        assertDoesNotThrow(() -> DangerGuard.assertFileMutationsInWorkspace("ls /tmp", guard));
    }
}
