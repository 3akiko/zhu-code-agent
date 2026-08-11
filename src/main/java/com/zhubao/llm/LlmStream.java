package com.zhubao.llm;

import java.util.concurrent.BlockingQueue;

/**
 * 一次 LLM 流式调用的句柄（M4，spec F5）：事件队列 + 取消 + 优雅等待。
 *
 * <p>{@link #cancel()} 中断后台流线程并投递中断事件，保证消费方不永久阻塞；
 * {@link #join()} 带超时等待流线程结束，供退出时优雅关闭（不泄漏 daemon 线程）。
 *
 * <p>实现为普通类而非 record：避免 record 存取器 {@code cancel()} 只返回值不执行
 * 的陷阱（调用方必须写 {@code stream.cancel()} 即触发取消）。
 */
public final class LlmStream {

    private final BlockingQueue<StreamEvent> events;
    private final Runnable cancelAction;
    private final Runnable joinAction;

    public LlmStream(BlockingQueue<StreamEvent> events, Runnable cancel, Runnable join) {
        this.events = events;
        this.cancelAction = cancel;
        this.joinAction = join;
    }

    public BlockingQueue<StreamEvent> events() {
        return events;
    }

    /** 取消本次流（幂等）：中断后台线程 + 关闭响应体 + 投递「已中断」事件 */
    public void cancel() {
        cancelAction.run();
    }

    /** 带超时等待流线程结束（退出时优雅关闭） */
    public void join() {
        joinAction.run();
    }
}
