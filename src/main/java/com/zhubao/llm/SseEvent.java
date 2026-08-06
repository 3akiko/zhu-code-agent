package com.zhubao.llm;

/**
 * 一条解析后的 SSE 事件。
 *
 * @param event 事件名（OpenAI 的流没有 event: 行，为 null）
 * @param data  data 字段内容（多行 data 以 \n 连接）
 */
public record SseEvent(String event, String data) {
}
