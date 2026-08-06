package com.zhubao.llm;

/**
 * 统一流事件模型：把 Anthropic / OpenAI 两类协议的 SSE 流收敛为同一组事件，
 * 供 TUI 渲染与上层消费（spec F6）。
 *
 * 事件契约：
 * <ul>
 *   <li>正常结束：最后必发 {@link StreamEnd}（含 stop_reason 与 token 用量）</li>
 *   <li>出错：只发 {@link Error}（不保证有 StreamEnd）</li>
 * </ul>
 *
 * M1 不含 ToolCall 事件（M2 再扩展）。
 */
public sealed interface StreamEvent {

    /** 正式回复的增量文本 */
    record TextDelta(String text) implements StreamEvent {}

    /** 扩展思考的增量文本（Claude extended thinking，灰色小字展示） */
    record ThinkingDelta(String text) implements StreamEvent {}

    /** 思考结束（Anthropic 携带 signature，供多轮回传） */
    record ThinkingComplete(String signature) implements StreamEvent {}

    /** 流式结束：stop_reason + token 用量 */
    record StreamEnd(String stopReason, int inputTokens, int outputTokens) implements StreamEvent {}

    /** 错误（消息已脱敏，不含 api_key） */
    record Error(String message) implements StreamEvent {}
}
