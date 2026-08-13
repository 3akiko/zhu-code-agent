package com.zhubao.llm;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单次 LLM 流式调用的 per-call 状态持有者（M5，spec F1）。
 *
 * <p>M1–M4 把流累积状态放在客户端实例字段上（同一实例并发 {@code stream()} 有数据竞争、
 * 取消标志永不复位）。M5 重构为「一个流一个状态对象」：每次 {@code stream()} 由
 * {@link AbstractStreamingClient#newStreamState()} 创建，worker 线程写入、cancel 闭包读取
 * （同一对象，可见性天然一致）；客户端实例退化为无状态（只读 config + 线程安全 HttpClient），
 * 可复用、可并发。
 *
 * <p>基类放两协议公共字段；协议特有累积字段放各自子类：
 * Anthropic（thinking + 单块 tool_use 累积）与 OpenAI（多 index tool_calls 累积）。
 */
abstract class StreamState {

    /** 当前流的响应体（供 cancel 关闭以打断阻塞读取；M4，spec F5 迁入 per-call 状态） */
    volatile InputStream activeBody;

    /** 当前流是否已被取消（幂等；per-call 新建，实例复用不串） */
    final AtomicBoolean cancelled = new AtomicBoolean(false);

    // 公共累积：token 统计与停止原因（协议无关）
    int inputTokens;
    int outputTokens;
    int cacheReadTokens;
    int cacheCreationTokens;
    String stopReason;
}

/** Anthropic 协议流状态：extended thinking + 单块 tool_use 累积（content_block_start→delta→stop） */
final class AnthropicStreamState extends StreamState {

    boolean inThinking;
    final StringBuilder thinkingAccum = new StringBuilder();
    String thinkingSignature;
    boolean inToolUse;
    String toolUseId;
    String toolUseName;
    final StringBuilder toolUseJsonAccum = new StringBuilder();
}

/** OpenAI 协议流状态：多 index tool_calls 并行累积（delta.tool_calls 按 index 分片到达） */
final class OpenAiStreamState extends StreamState {

    final Map<Integer, ToolAccum> toolAccums = new LinkedHashMap<>();

    static final class ToolAccum {
        String id = "";
        String name = "";
        final StringBuilder args = new StringBuilder();
    }
}
