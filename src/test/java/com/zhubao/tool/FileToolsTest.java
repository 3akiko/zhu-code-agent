package com.zhubao.tool;

import com.zhubao.tool.builtin.EditFileTool;
import com.zhubao.tool.builtin.GlobTool;
import com.zhubao.tool.builtin.GrepTool;
import com.zhubao.tool.builtin.ReadFileTool;
import com.zhubao.tool.builtin.WriteFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileToolsTest {

    @TempDir
    Path ws;

    private ToolCall call(String name, Map<String, Object> args) {
        return new ToolCall("call-1", name, ToolCall.json(args));
    }

    @Test
    void readFileReadsFullContent() throws Exception {
        Files.writeString(ws.resolve("a.txt"), "line1\nline2\nline3\n");
        ToolResult r = new ReadFileTool(new PathGuard(ws)).execute(call("read_file", Map.of("path", "a.txt")));
        assertFalse(r.isError(), r.output());
        assertEquals("line1\nline2\nline3\n", r.output());
    }

    @Test
    void readFileSupportsOffsetLimit() throws Exception {
        Files.writeString(ws.resolve("a.txt"), "l1\nl2\nl3\nl4\nl5\n");
        ToolResult r = new ReadFileTool(new PathGuard(ws)).execute(call("read_file", Map.of("path", "a.txt", "offset", 2, "limit", 2)));
        assertFalse(r.isError(), r.output());
        assertEquals("l2\nl3\n", r.output());
    }

    @Test
    void readFileMissingReturnsError() {
        ToolResult r = new ReadFileTool(new PathGuard(ws)).execute(call("read_file", Map.of("path", "nope.txt")));
        assertTrue(r.isError());
        assertTrue(r.output().contains("不存在"));
    }

    @Test
    void readFileOutsideWorkspaceRejected() {
        ToolResult r = new ReadFileTool(new PathGuard(ws)).execute(call("read_file", Map.of("path", "../secret.txt")));
        assertTrue(r.isError());
        assertTrue(r.output().contains("越界"));
    }

    @Test
    void writeFileCreatesAndOverwrites() throws Exception {
        WriteFileTool tool = new WriteFileTool(new PathGuard(ws));
        ToolResult r1 = tool.execute(call("write_file", Map.of("path", "dir/n.txt", "content", "hello")));
        assertFalse(r1.isError(), r1.output());
        assertEquals("hello", Files.readString(ws.resolve("dir/n.txt")));

        ToolResult r2 = tool.execute(call("write_file", Map.of("path", "dir/n.txt", "content", "world")));
        assertFalse(r2.isError(), r2.output());
        assertEquals("world", Files.readString(ws.resolve("dir/n.txt")));
    }

    @Test
    void writeFileToGitDirRejected() throws Exception {
        Files.createDirectories(ws.resolve(".git"));
        ToolResult r = new WriteFileTool(new PathGuard(ws)).execute(call("write_file", Map.of("path", ".git/config", "content", "x")));
        assertTrue(r.isError());
        assertTrue(r.output().contains(".git"));
        assertFalse(Files.exists(ws.resolve(".git/config")));
    }

    @Test
    void editFileUniqueReplace() throws Exception {
        Files.writeString(ws.resolve("a.txt"), "foo bar foo");
        ToolResult r = new EditFileTool(new PathGuard(ws)).execute(call("edit_file",
                Map.of("path", "a.txt", "old_string", "bar", "new_string", "BAZ")));
        assertFalse(r.isError(), r.output());
        assertEquals("foo BAZ foo", Files.readString(ws.resolve("a.txt")));
    }

    @Test
    void editFileNotFoundReturnsReadableError() throws Exception {
        Files.writeString(ws.resolve("a.txt"), "hello");
        ToolResult r = new EditFileTool(new PathGuard(ws)).execute(call("edit_file",
                Map.of("path", "a.txt", "old_string", "zzz", "new_string", "x")));
        assertTrue(r.isError());
        assertTrue(r.output().contains("未找到"));
    }

    @Test
    void editFileMultipleMatchesRejected() throws Exception {
        Files.writeString(ws.resolve("a.txt"), "abc abc abc");
        ToolResult r = new EditFileTool(new PathGuard(ws)).execute(call("edit_file",
                Map.of("path", "a.txt", "old_string", "abc", "new_string", "x")));
        assertTrue(r.isError());
        assertTrue(r.output().contains("不唯一"));
        assertEquals("abc abc abc", Files.readString(ws.resolve("a.txt")));
    }

    @Test
    void grepFindsMatchesWithLineNumbers() throws Exception {
        Files.writeString(ws.resolve("x.java"), "public class X {}\n// TODO fix\n");
        Files.createDirectories(ws.resolve("sub"));
        Files.writeString(ws.resolve("sub/y.txt"), "TODO here\n");
        ToolResult r = new GrepTool(new PathGuard(ws)).execute(call("grep", Map.of("pattern", "TODO")));
        assertFalse(r.isError(), r.output());
        assertTrue(r.output().contains("x.java:2:"));
        assertTrue(r.output().contains("sub/y.txt:1:"));
    }

    @Test
    void grepNoMatchAndBadRegex() throws Exception {
        Files.writeString(ws.resolve("x.txt"), "hello");
        GrepTool tool = new GrepTool(new PathGuard(ws));
        ToolResult none = tool.execute(call("grep", Map.of("pattern", "zzz")));
        assertFalse(none.isError());
        assertTrue(none.output().contains("未找到"));
        ToolResult bad = tool.execute(call("grep", Map.of("pattern", "([")));
        assertTrue(bad.isError());
    }

    @Test
    void globMatchesPatterns() throws Exception {
        Files.createDirectories(ws.resolve("src/main/java"));
        Files.writeString(ws.resolve("src/main/java/A.java"), "a");
        Files.writeString(ws.resolve("src/main/B.java"), "b");
        Files.writeString(ws.resolve("README.md"), "r");
        ToolResult r = new GlobTool(new PathGuard(ws)).execute(call("glob", Map.of("pattern", "src/**/*.java")));
        assertFalse(r.isError(), r.output());
        assertTrue(r.output().contains("src/main/java/A.java"));
        assertTrue(r.output().contains("src/main/B.java"));
        assertFalse(r.output().contains("README.md"));
    }
}
