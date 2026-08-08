package com.zhubao.tool;

import com.zhubao.tool.builtin.BashTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BashToolTest {

    @TempDir
    Path ws;

    private ToolCall call(Map<String, Object> args) {
        return new ToolCall("call-1", "bash", ToolCall.json(args));
    }

    @Test
    void echoRunsInWorkspace() {
        ToolResult r = new BashTool(new PathGuard(ws)).execute(call(Map.of("command", "pwd")));
        assertFalse(r.isError(), r.output());
        assertTrue(r.output().trim().endsWith(ws.toAbsolutePath().normalize().toString()));
    }

    @Test
    void nonZeroExitIsError() {
        ToolResult r = new BashTool(new PathGuard(ws)).execute(call(Map.of("command", "exit 3")));
        assertTrue(r.isError());
        assertTrue(r.output().contains("退出码 3"));
    }

    @Test
    void noStdinProvided() {
        ToolResult r = new BashTool(new PathGuard(ws)).execute(call(Map.of("command", "cat")));
        assertFalse(r.isError(), r.output());
        assertEquals("", r.output().trim(), "无 stdin 时 cat 应读到 EOF 输出为空");
    }

    @Test
    void rmOutsideWorkspaceRejectedWithoutSideEffect() throws Exception {
        Files.writeString(ws.resolve("keep.txt"), "keep");
        Path outside = Files.createTempDirectory("outside-rm");
        Files.writeString(outside.resolve("victim.txt"), "do not delete");
        try {
            ToolResult r = new BashTool(new PathGuard(ws)).execute(call(Map.of("command", "rm -rf " + outside)));
            assertTrue(r.isError(), "应拒绝 cwd 外的 rm -rf");
            assertTrue(r.output().contains("拒绝"));
            assertTrue(Files.exists(outside.resolve("victim.txt")), "目标必须仍在（未执行）");
        } finally {
            outside.toFile().deleteOnExit();
        }
    }

    @Test
    void rmWithShellExpansionRejectedWithoutSideEffect() {
        ToolResult r = new BashTool(new PathGuard(ws)).execute(call(Map.of("command", "rm -rf $HOME/m2-evil")));
        assertTrue(r.isError(), "含 shell 展开的 rm -rf 应被拒绝");
        assertTrue(r.output().contains("拒绝"));
    }

    @Test
    void rmWithoutRfOutsideRejectedWithoutSideEffect() throws Exception {
        Path outside = Files.createTempDirectory("outside-rmf");
        Files.writeString(outside.resolve("v.txt"), "keep");
        try {
            ToolResult r = new BashTool(new PathGuard(ws)).execute(call(Map.of("command", "rm -f " + outside.resolve("v.txt"))));
            assertTrue(r.isError(), "不带 -rf 的 rm 越界也应拒绝");
            assertTrue(r.output().contains("拒绝"));
            assertTrue(Files.exists(outside.resolve("v.txt")), "目标必须仍在（未执行）");
        } finally {
            outside.toFile().deleteOnExit();
        }
    }

    @Test
    void mvAndCpOutsideRejectedWithoutSideEffect() throws Exception {
        Files.writeString(ws.resolve("src.txt"), "x");
        Path outside = Files.createTempDirectory("outside-mv");
        Files.writeString(outside.resolve("dest.txt"), "keep");
        try {
            ToolResult mv = new BashTool(new PathGuard(ws)).execute(call(Map.of("command", "mv src.txt " + outside.resolve("dest.txt"))));
            assertTrue(mv.isError(), "mv 目标越界应拒绝");
            assertTrue(mv.output().contains("拒绝"));
            ToolResult cp = new BashTool(new PathGuard(ws)).execute(call(Map.of("command", "cp src.txt " + outside.resolve("dest.txt"))));
            assertTrue(cp.isError(), "cp 目标越界应拒绝");
            assertTrue(Files.exists(outside.resolve("dest.txt")), "目标文件应未被覆写");
        } finally {
            outside.toFile().deleteOnExit();
        }
    }

    @Test
    void rmInsideWorkspaceAllowedButIsErrorForMissing() {
        ToolResult r = new BashTool(new PathGuard(ws)).execute(call(Map.of("command", "rm -rf ./nonexistent-dir")));
        // rm -rf 不存在目标：shell 返回 0（未报错），属于工作区内放行场景
        assertFalse(r.isError(), r.output());
    }

    @Test
    void timeoutKillsCommand() {
        long start = System.currentTimeMillis();
        ToolResult r = new BashTool(new PathGuard(ws)).execute(call(Map.of("command", "sleep 60")));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(r.isError());
        assertTrue(r.output().contains("超时"), "output was: [" + r.output() + "]");
        assertTrue(elapsed < 35_000, "应在 ~30s 内被 kill，实际 " + elapsed + "ms");
    }
}
