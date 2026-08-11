package com.zhubao.llm;

import java.util.concurrent.BlockingQueue;

/**
 * LLM 客户端统一接口（spec F6）。
 *
 * <p>{@link #stream(ChatRequest)} 内部在后台线程发起 HTTP/SSE 流式请求，并把
 * 增量事件写入返回的 {@link LlmStream}；调用方（TUI 主线程）轮询消费事件，
 * 可调用 cancel() 中断本次生成、join() 等待流线程结束（M4，spec F5）。
 *
 * <p>事件契约：正常结束最后是 {@link StreamEvent.StreamEnd}；出错只发
 * {@link StreamEvent.Error}。
 */
public interface LlmClient {

    /** 发起流式对话请求，返回流句柄（事件队列 + 取消/等待，后台线程异步生产）。 */
    LlmStream stream(ChatRequest request);
}
