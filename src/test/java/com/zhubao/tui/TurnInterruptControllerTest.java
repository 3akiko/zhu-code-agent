package com.zhubao.tui;

import com.zhubao.llm.LlmStream;
import org.junit.jupiter.api.Test;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本轮中断控制器单测（M4 spec F5 + review 加固）：
 * 单次 Ctrl+C 取消当前流但不退出；1.5s 内二次 Ctrl+C 触发逃生门；跨轮窗口重置。
 */
class TurnInterruptControllerTest {

    private static LlmStream cancellableStream(AtomicBoolean cancelled) {
        return new LlmStream(new LinkedBlockingQueue<>(), () -> cancelled.set(true), () -> { });
    }

    @Test
    void singleInterruptCancelsStreamWithoutExitRequest() {
        TurnInterruptController c = new TurnInterruptController(null);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        try {
            c.beginTurn();
            c.onStreamCreated(cancellableStream(cancelled));
            c.onCtrlC(); // 单次
            assertFalse(c.consumeExitRequest(), "单次 Ctrl+C 不应触发退出");
            assertTrue(cancelled.get(), "单次 Ctrl+C 应取消当前流");
        } finally {
            c.endTurn();
        }
    }

    @Test
    void doubleInterruptWithinWindowRequestsExit() {
        TurnInterruptController c = new TurnInterruptController(null);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        try {
            c.beginTurn();
            c.onStreamCreated(cancellableStream(cancelled));
            c.onCtrlC();
            c.onCtrlC(); // 1.5s 内第二次 → 逃生门
            assertTrue(c.consumeExitRequest(), "二次 Ctrl+C 应触发逃生门退出");
            assertTrue(cancelled.get(), "逃生门也应取消在途工作");
        } finally {
            c.endTurn();
        }
    }

    @Test
    void doubleInterruptAcrossTurnsDoesNotRequestExit() {
        TurnInterruptController c = new TurnInterruptController(null);
        c.beginTurn();
        c.onCtrlC();
        c.endTurn();
        try {
            c.beginTurn(); // 新一轮：窗口重置
            c.onCtrlC();
            assertFalse(c.consumeExitRequest(), "跨轮 Ctrl+C 不应触发退出");
        } finally {
            c.endTurn();
        }
    }

    // M4 review-P3：逃生门粘性——跨 runTurn 不清零，直到被 consumeExitRequest 消费
    @Test
    void escapeRequestStickyUntilConsumed() {
        TurnInterruptController c = new TurnInterruptController(null);
        c.beginTurn();
        c.onCtrlC();
        c.onCtrlC(); // 逃生门
        c.endTurn();
        c.beginTurn(); // 新一轮 beginTurn 不应吞掉逃生门
        assertTrue(c.consumeExitRequest(), "逃生门跨 runTurn 不清零");
        assertFalse(c.consumeExitRequest(), "消费后应清除");
        c.endTurn();
    }
}
