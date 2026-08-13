package com.zhubao.agent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在途子任务线程协调器（M5，spec F3.8 级联取消）。
 *
 * <p>每个子任务启动时 {@link #register(Thread)}、结束 {@link #unregister(Thread)}；
 * Ctrl+C 时 {@link TurnInterruptController} 调 {@link #cancelAll()} 级联 interrupt
 * 所有在途子任务线程——子任务在 LLM 生成阶段 poll 被中断、工具阶段
 * {@code isInterrupted} 检查 break、权限确认 readLine 被打断，半成品不写回（spec F3.8）。
 */
public final class SubagentCoordinator {

    private static final Set<Thread> SUBAGENT_THREADS = ConcurrentHashMap.newKeySet();

    private SubagentCoordinator() {
    }

    /** 登记一个在途子任务线程（子任务启动时调用） */
    public static void register(Thread thread) {
        SUBAGENT_THREADS.add(thread);
    }

    /** 注销一个子任务线程（子任务结束时调用，finally 保证） */
    public static void unregister(Thread thread) {
        SUBAGENT_THREADS.remove(thread);
    }

    /** 级联取消：interrupt 全部在途子任务线程（Ctrl+C 路径，M5 spec F3.8） */
    public static void cancelAll() {
        for (Thread t : SUBAGENT_THREADS) {
            t.interrupt();
        }
    }

    /** 在途子任务数（测试断言用） */
    public static int activeCount() {
        return SUBAGENT_THREADS.size();
    }
}
