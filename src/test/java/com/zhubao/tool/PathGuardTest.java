package com.zhubao.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PathGuardTest {

    @TempDir
    Path ws;

    private PathGuard guard() {
        return new PathGuard(ws);
    }

    @Test
    void resolvesRelativePathInsideWorkspace() throws Exception {
        Files.writeString(ws.resolve("a.txt"), "x");
        Path p = guard().resolveInWorkspace("a.txt");
        assertEquals(ws.toRealPath().resolve("a.txt"), p);
    }

    @Test
    void resolvesAbsolutePathInsideWorkspace() throws Exception {
        Path p = guard().resolveInWorkspace(ws.resolve("b.txt").toString());
        assertEquals(ws.toRealPath().resolve("b.txt"), p);
    }

    @Test
    void parentDotDotEscapeRejected() {
        ToolException ex = assertThrows(ToolException.class, () -> guard().resolveInWorkspace("../evil.txt"));
        assertTrue(ex.getMessage().contains("越界"));
    }

    @Test
    void absolutePathOutsideRejected() {
        ToolException ex = assertThrows(ToolException.class, () -> guard().resolveInWorkspace("/etc/passwd"));
        assertTrue(ex.getMessage().contains("越界"));
    }

    @Test
    void homeDirRejected() {
        ToolException ex = assertThrows(ToolException.class, () -> guard().resolveInWorkspace(System.getProperty("user.home")));
        assertTrue(ex.getMessage().contains("越界"));
    }

    @Test
    void symlinkPointingOutsideRejected() throws Exception {
        Path outside = Files.createTempDirectory("outside");
        try {
            Path link = ws.resolve("link");
            Files.createSymbolicLink(link, outside.resolve("secret.txt"));
            Files.writeString(outside.resolve("secret.txt"), "secret");
            ToolException ex = assertThrows(ToolException.class, () -> guard().resolveInWorkspace("link"));
            assertTrue(ex.getMessage().contains("越界"), ex.getMessage());
        } finally {
            outside.toFile().deleteOnExit();
        }
    }

    @Test
    void nonExistentPathInsideWorkspaceAllowed() throws Exception {
        Path p = guard().resolveInWorkspace("newdir/newfile.txt");
        assertEquals(ws.toRealPath().resolve("newdir/newfile.txt"), p);
    }

    @Test
    void gitDirWriteForbidden() throws Exception {
        Path git = ws.resolve(".git");
        Files.createDirectories(git);
        PathGuard g = guard();
        ToolException ex = assertThrows(ToolException.class, () -> g.assertNotForbidden(git.resolve("config")));
        assertTrue(ex.getMessage().contains(".git"));
    }

    @Test
    void homeAgentDirForbidden() {
        Path homeAgent = Path.of(System.getProperty("user.home"), ".zhu-code-agent", "sessions");
        PathGuard g = guard();
        ToolException ex = assertThrows(ToolException.class, () -> g.assertNotForbidden(homeAgent));
        assertTrue(ex.getMessage().contains(".zhu-code-agent"));
    }

    @Test
    void blankPathRejected() {
        assertThrows(ToolException.class, () -> guard().resolveInWorkspace("  "));
    }
}
