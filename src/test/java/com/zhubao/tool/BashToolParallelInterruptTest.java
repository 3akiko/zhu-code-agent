package com.zhubao.tool;

import com.zhubao.tool.builtin.BashTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M5 review 修复（2026-08-13）：并行 bash 时取消必须销毁**全部**在途进程。
 * 旧实现 `currentProcess` 单引用会被覆盖，cancel 只销毁最后启动的进程，其余泄漏。
 */
class BashToolParallelInterruptTest {

    @TempDir
    Path ws;

    @Test
    void cancelDestroysAllParallelProcesses() throws Exception {
        BashTool tool = new BashTool(new PathGuard(ws));
        int n = 3;
        CountDownLatch started = new CountDownLatch(n);
        AtomicInteger finishedCount = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int idx = i;
            Thread t = new Thread(() -> {
                started.countDown();
                tool.execute(new ToolCall("b" + idx, "bash",
                        ToolCall.json(Map.of("command", "sleep 30"))));
                finishedCount.incrementAndGet();
            });
            t.setDaemon(true);
            workers.add(t);
            t.start();
        }
        // 等全部 bash 启动（覆盖旧实现单引用的时序窗口）
        assertTrue(started.await(3, TimeUnit.SECONDS), "三个 bash 都应启动");
        Thread.sleep(300);

        long begin = System.currentTimeMillis();
        tool.cancel();
        for (Thread t : workers) {
            t.join(5_000);
        }
        long elapsedMs = System.currentTimeMillis() - begin;

        assertEquals(n, finishedCount.get(), "cancel 后全部 bash 都应快速结束");
        assertTrue(elapsedMs < 5_000, "cancel 后不应等满 30s sleep，实际 " + elapsedMs + "ms");
    }
}
