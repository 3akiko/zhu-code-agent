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
 * M1 不含 ToolCall 事件；M2 新增 ToolCall 事件（工具调用声明）。
 */
public sealed interface StreamEvent {

    /** 正式回复的增量文本 */
    record TextDelta(String text) implements StreamEvent {}

    /** 扩展思考的增量文本（Claude extended thinking，灰色小字展示） */
    record ThinkingDelta(String text) implements StreamEvent {}

    /** 思考结束（Anthropic 携带 signature，供多轮回传） */
    record ThinkingComplete(String signature) implements StreamEvent {}

    /**
     * 工具调用声明（M2，spec F8）：anthropic tool_use / openai function_call 统一收敛。
     * argumentsJson 为完整的参数 JSON 字符串（由客户端累积拼接）。
     */
    record ToolCall(String id, String name, String argumentsJson) implements StreamEvent {}

    /**
     * 流式结束：stop_reason + token 用量 + prompt 缓存命中指标（M4，spec F4）。
     *
     * <p>cacheReadTokens：Anthropic cache_read_input_tokens / OpenAI prompt_tokens_details.cached_tokens
     * / DeepSeek prompt_cache_hit_tokens；cacheCreationTokens：Anthropic cache_creation_input_tokens（其余 0）。
     */
    record StreamEnd(String stopReason, int inputTokens, int outputTokens,
                     int cacheReadTokens, int cacheCreationTokens) implements StreamEvent {

        /** 兼容旧调用（M1–M3）：缓存指标默认 0 */
        public StreamEnd(String stopReason, int inputTokens, int outputTokens) {
            this(stopReason, inputTokens, outputTokens, 0, 0);
        }
    }

    /** 错误（消息已脱敏，不含 api_key） */
    record Error(String message) implements StreamEvent {}
}
