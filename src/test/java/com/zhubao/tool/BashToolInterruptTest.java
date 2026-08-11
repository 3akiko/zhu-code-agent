package com.zhubao.tool;

import com.zhubao.tool.builtin.BashTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具中断（M4，spec F5）：慢命令执行中 interrupt / cancel 均快速返回且不遗留进程。
 */
class BashToolInterruptTest {

    @TempDir
    Path ws;

    private ToolCall sleepCall() {
        return new ToolCall("call_1", "bash", ToolCall.json(Map.of("command", "sleep 30")));
    }

    @Test
    void interruptDuringExecutionReturnsInterruptedAndKillsProcess() throws Exception {
        BashTool tool = new BashTool(new PathGuard(ws));
        AtomicReference<ToolResult> ref = new AtomicReference<>();
        Thread t = new Thread(() -> ref.set(tool.execute(sleepCall())));
        t.start();
        Thread.sleep(300);
        long start = System.currentTimeMillis();
        t.interrupt();
        t.join(5_000);
        assertFalse(t.isAlive(), "中断后应快速返回");
        assertTrue(System.currentTimeMillis() - start < 5_000, "不应等满 30s");
        ToolResult r = ref.get();
        assertNotNull(r);
        assertTrue(r.isError());
        assertTrue(r.output().contains("被中断"), r.output());
    }

    @Test
    void cancelDestroysProcessAndReturnsPromptly() throws Exception {
        BashTool tool = new BashTool(new PathGuard(ws));
        AtomicReference<ToolResult> ref = new AtomicReference<>();
        Thread t = new Thread(() -> ref.set(tool.execute(sleepCall())));
        t.start();
        Thread.sleep(300);
        long start = System.currentTimeMillis();
        tool.cancel();
        t.join(5_000);
        assertFalse(t.isAlive(), "cancel 后应快速返回");
        assertTrue(System.currentTimeMillis() - start < 5_000);
        ToolResult r = ref.get();
        assertNotNull(r);
        assertTrue(r.isError(), "被销毁的进程应返回错误（退出码或被中断）");
    }
}
