package com.zhubao.llm;

import java.util.concurrent.BlockingQueue;

/**
 * LLM 客户端统一接口（spec F6）。
 *
 * <p>{@link #stream(ChatRequest)} 内部在后台线程发起 HTTP/SSE 流式请求，并把
 * 增量事件写入返回的 {@link BlockingQueue}；调用方（TUI 主线程）轮询消费。
 *
 * <p>事件契约：正常结束最后是 {@link StreamEvent.StreamEnd}；出错只发
 * {@link StreamEvent.Error}。
 */
public interface LlmClient {

    /** 发起流式对话请求，返回事件队列（后台线程异步生产）。 */
    BlockingQueue<StreamEvent> stream(ChatRequest request);
}
