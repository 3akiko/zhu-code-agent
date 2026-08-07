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
        assertDoesNotThrow(() -> DangerGuard.assertRmTargetsInWorkspace("rm -rf ./tmp", guard));
        assertDoesNotThrow(() -> DangerGuard.assertRmTargetsInWorkspace("rm -rf *.tmp", guard));
        assertDoesNotThrow(() -> DangerGuard.assertRmTargetsInWorkspace("rm -rf build/ out/", guard));
    }

    @Test
    void rmTargetsOutsideWorkspaceRejected() {
        PathGuard guard = new PathGuard(ws);
        assertThrows(ToolException.class, () -> DangerGuard.assertRmTargetsInWorkspace("rm -rf /tmp/x", guard));
        assertThrows(ToolException.class, () -> DangerGuard.assertRmTargetsInWorkspace("rm -rf ../x", guard));
        assertThrows(ToolException.class, () -> DangerGuard.assertRmTargetsInWorkspace("rm -rf /home/user/x", guard));
        assertThrows(ToolException.class, () -> DangerGuard.assertRmTargetsInWorkspace("rm -rf ../*", guard));
    }
}
