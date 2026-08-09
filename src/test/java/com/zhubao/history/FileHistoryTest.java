package com.zhubao.history;

import com.zhubao.tool.PathGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FileHistory 单测（M3 spec F3）：快照落盘/重载/discardLast/超大文件/损坏跳过；
 * undo 单步与新建删除；rewind 多检查点顺序；跨会话新实例仍可回滚；越界快照跳过。
 */
class FileHistoryTest {

    @TempDir
    Path ws;

    private FileHistory history() {
        return new FileHistory(ws.resolve("snap"), new PathGuard(ws));
    }

    @Test
    void snapshotPersistsAndReloads() throws Exception {
        Files.writeString(ws.resolve("a.txt"), "hello");
        FileHistory h = history();
        FileHistory.SnapshotOutcome out = h.snapshotBefore("s1", "a.txt", "edit_file: a.txt");
        assertTrue(out.taken());

        // 新实例（跨会话语义）读取
        FileHistory h2 = history();
        var list = h2.list("s1");
        assertEquals(1, list.size());
        FileCheckpoint cp = list.get(0);
        assertTrue(cp.existed());
        assertEquals("hello", cp.beforeContent());
        assertEquals("edit_file: a.txt", cp.summary());
        assertTrue(cp.path().endsWith("a.txt"));
    }

    @Test
    void snapshotNewFileRecordsNotExisted() {
        FileHistory h = history();
        var out = h.snapshotBefore("s1", "new.txt", "write_file: new.txt");
        assertTrue(out.taken());
        FileCheckpoint cp = h.list("s1").get(0);
        assertFalse(cp.existed());
        assertNull(cp.beforeContent());
    }

    @Test
    void oversizeFileSkipped() throws Exception {
        // 11MB 文件
        byte[] big = new byte[11 * 1024 * 1024];
        java.util.Arrays.fill(big, (byte) 'x');
        Files.write(ws.resolve("big.bin"), big);
        FileHistory h = history();
        var out = h.snapshotBefore("s1", "big.bin", "write_file: big.bin");
        assertFalse(out.taken());
        assertTrue(out.skippedOversize());
        assertTrue(h.list("s1").isEmpty());
    }

    @Test
    void outOfBoundsSnapshotSkipped() {
        FileHistory h = history();
        var out = h.snapshotBefore("s1", "../outside.txt", "edit_file");
        assertFalse(out.taken());
        assertTrue(h.list("s1").isEmpty());
    }

    @Test
    void discardLastRemovesNewest() throws Exception {
        Files.writeString(ws.resolve("a.txt"), "v0");
        FileHistory h = history();
        h.snapshotBefore("s1", "a.txt", "e1");
        Files.writeString(ws.resolve("a.txt"), "v1");
        h.snapshotBefore("s1", "a.txt", "e2");
        assertEquals(2, h.list("s1").size());
        h.discardLast("s1");
        assertEquals(1, h.list("s1").size());
        assertEquals("v0", h.list("s1").get(0).beforeContent());
    }

    @Test
    void corruptIndexSkippedNotCrashed() throws Exception {
        Path dir = ws.resolve("snap").resolve("s1");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("checkpoints.json"), "not json {{{");
        FileHistory h = history();
        assertTrue(h.list("s1").isEmpty());
        // 损坏后仍可追加新检查点
        Files.writeString(ws.resolve("a.txt"), "x");
        h.snapshotBefore("s1", "a.txt", "e");
        assertEquals(1, h.list("s1").size());
    }

    @Test
    void undoRestoresContent() throws Exception {
        Files.writeString(ws.resolve("a.txt"), "hello");
        FileHistory h = history();
        h.snapshotBefore("s1", "a.txt", "edit_file: a.txt");
        Files.writeString(ws.resolve("a.txt"), "world");
        RollbackResult r = h.undo("s1");
        assertTrue(r.ok(), r.message());
        assertEquals("hello", Files.readString(ws.resolve("a.txt")));
        assertTrue(r.actions().get(0).contains("已恢复"));
        assertTrue(h.list("s1").isEmpty());
    }

    @Test
    void undoCreatedFileDeletesIt() throws Exception {
        FileHistory h = history();
        h.snapshotBefore("s1", "new.txt", "write_file: new.txt");
        Files.writeString(ws.resolve("new.txt"), "data");
        RollbackResult r = h.undo("s1");
        assertTrue(r.ok(), r.message());
        assertFalse(Files.exists(ws.resolve("new.txt")));
        assertTrue(r.actions().get(0).contains("已删除新建文件"));
    }

    @Test
    void rewindOrderRestoresCorrectState() throws Exception {
        Files.writeString(ws.resolve("a.txt"), "v0");
        FileHistory h = history();
        h.snapshotBefore("s1", "a.txt", "e1");
        Files.writeString(ws.resolve("a.txt"), "v1");
        h.snapshotBefore("s1", "a.txt", "e2");
        Files.writeString(ws.resolve("a.txt"), "v2");

        // rewind 到 cp0（最旧）：倒序恢复 cp1(v1) → cp0(v0)，最终 v0
        RollbackResult r = h.rewindTo("s1", 0);
        assertTrue(r.ok(), r.message());
        assertEquals("v0", Files.readString(ws.resolve("a.txt")));
        assertTrue(h.list("s1").isEmpty());
    }

    @Test
    void rewindToMiddleKeepsEarlierCheckpoints() throws Exception {
        Files.writeString(ws.resolve("a.txt"), "v0");
        FileHistory h = history();
        h.snapshotBefore("s1", "a.txt", "e1");
        Files.writeString(ws.resolve("a.txt"), "v1");
        h.snapshotBefore("s1", "a.txt", "e2");
        Files.writeString(ws.resolve("a.txt"), "v2");

        // rewind 到 cp1：只撤销 e2，回到 v1；cp0 保留
        RollbackResult r = h.rewindTo("s1", 1);
        assertTrue(r.ok(), r.message());
        assertEquals("v1", Files.readString(ws.resolve("a.txt")));
        assertEquals(1, h.list("s1").size());
        assertEquals("v0", h.list("s1").get(0).beforeContent());
    }

    @Test
    void crossSessionNewInstanceStillRollsBack() throws Exception {
        Files.writeString(ws.resolve("a.txt"), "orig");
        FileHistory h1 = history();
        h1.snapshotBefore("s1", "a.txt", "edit_file");
        Files.writeString(ws.resolve("a.txt"), "changed");

        // 模拟重启：新实例（同快照根目录）
        FileHistory h2 = history();
        RollbackResult r = h2.undo("s1");
        assertTrue(r.ok(), r.message());
        assertEquals("orig", Files.readString(ws.resolve("a.txt")));
    }

    @Test
    void undoEmptyReturnsNone() {
        RollbackResult r = history().undo("s1");
        assertFalse(r.ok());
        assertTrue(r.message().contains("没有"));
    }

    @Test
    void rewindInvalidIndexRejected() throws Exception {
        Files.writeString(ws.resolve("a.txt"), "v0");
        FileHistory h = history();
        h.snapshotBefore("s1", "a.txt", "e");
        RollbackResult r = h.rewindTo("s1", 5);
        assertFalse(r.ok());
        assertTrue(r.message().contains("越界"));
        assertEquals(1, h.list("s1").size());
    }
}
