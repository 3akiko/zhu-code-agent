package com.zhubao.agent;

/**
 * 子任务嵌套深度上下文（M5，spec F3.5，review 修复 2026-08-13）。
 *
 * <p>深度沿「父→子→孙」执行链传递。因为 {@code ParallelToolExecutor} 用虚拟线程池
 * （每任务新线程）执行工具，普通 {@link ThreadLocal} 不会自动传播到新虚拟线程——
 * 本类作为显式传播通道：并行段提交前捕获父线程深度、虚拟线程内恢复，
 * 使「子任务内再派 task」时能读到正确的当前深度（否则嵌套深度护栏失效）。
 */
public final class AgentDepth {

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private AgentDepth() {
    }

    /** 当前执行链的子任务嵌套深度（主 agent = 0） */
    public static int get() {
        return DEPTH.get();
    }

    /** 设置当前线程的深度（runSubtask 进入子任务层前调用） */
    public static void set(int depth) {
        DEPTH.set(depth);
    }

    /** 清理（虚拟线程结束时调用，防止 ThreadLocal 残留） */
    public static void clear() {
        DEPTH.remove();
    }
}
