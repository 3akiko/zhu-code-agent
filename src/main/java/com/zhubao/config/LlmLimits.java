package com.zhubao.config;

import java.util.Map;

/**
 * 模型参数解析（M4，spec F7）：内置「模型 → 上下文窗口 / 输出上限」映射表。
 *
 * <p>ProviderConfig 显式配置优先；未命中表时用保守默认（上下文 32K、输出 64000/8192）。
 * 上下文窗口用于占用百分比与告警/压缩阈值；输出上限解决 AnthropicClient 写死
 * 8192/64000（如 claude-opus + thinking 应为 32000）的问题。
 */
public final class LlmLimits {

    /** 单个模型的上下文窗口与输出上限（thinking / plain 分列） */
    public record ModelLimit(int contextWindow, int maxPlain, int maxThinking) {
    }

    /** 未命中表时的保守默认上下文窗口 */
    public static final int DEFAULT_CONTEXT_WINDOW = 32 * 1024;
    /** 非思考模式默认最大输出 */
    public static final int DEFAULT_MAX_PLAIN = 8192;
    /** 思考模式默认最大输出 */
    public static final int DEFAULT_MAX_THINKING = 64 * 1024;

    private static final Map<String, ModelLimit> TABLE = Map.ofEntries(
            Map.entry("deepseek-v4-flash", new ModelLimit(1_000_000, 8192, 64000)),
            Map.entry("deepseek-v4-pro", new ModelLimit(1_000_000, 8192, 64000)),
            Map.entry("deepseek-chat", new ModelLimit(64 * 1024, 8192, 64000)),
            Map.entry("deepseek-reasoner", new ModelLimit(64 * 1024, 8192, 64000)),
            Map.entry("claude-sonnet-4-5", new ModelLimit(200 * 1024, 8192, 64000)),
            Map.entry("claude-opus-4", new ModelLimit(200 * 1024, 8192, 32000)),
            Map.entry("gpt-4o", new ModelLimit(128 * 1024, 8192, 64000))
    );

    private LlmLimits() {
    }

    /** 按模型名查表；未命中返回 null */
    public static ModelLimit byModel(String model) {
        return model == null ? null : TABLE.get(model);
    }

    public static int defaultContextWindow() {
        return DEFAULT_CONTEXT_WINDOW;
    }
}
