package com.zhubao.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模型表解析（M4，spec F7）：命中表 / 未命中默认 / 配置覆盖。
 */
class LlmLimitsTest {

    @Test
    void deepseekV4ModelsResolveToOneMillionWindow() {
        assertEquals(1_000_000, LlmLimits.byModel("deepseek-v4-flash").contextWindow());
        assertEquals(1_000_000, LlmLimits.byModel("deepseek-v4-pro").contextWindow());
    }

    @Test
    void unknownModelFallsBackToConservativeDefaults() {
        ProviderConfig cfg = new ProviderConfig();
        cfg.setModel("some-future-model");
        assertEquals(LlmLimits.DEFAULT_CONTEXT_WINDOW, cfg.effectiveContextWindow());
        assertEquals(LlmLimits.DEFAULT_MAX_PLAIN, cfg.effectiveMaxTokens(false));
        assertEquals(LlmLimits.DEFAULT_MAX_THINKING, cfg.effectiveMaxTokens(true));
    }

    @Test
    void configuredValuesOverrideModelTable() {
        ProviderConfig cfg = new ProviderConfig();
        cfg.setModel("deepseek-v4-flash");
        cfg.setContextWindow(2000);   // 测试用小窗口
        cfg.setMaxTokens(4096);
        assertEquals(2000, cfg.effectiveContextWindow());
        assertEquals(4096, cfg.effectiveMaxTokens(true));
        assertEquals(4096, cfg.effectiveMaxTokens(false));
    }

    @Test
    void claudeOpusThinkingCapsAt32k() {
        ProviderConfig cfg = new ProviderConfig();
        cfg.setModel("claude-opus-4");
        assertEquals(32000, cfg.effectiveMaxTokens(true));
        assertEquals(8192, cfg.effectiveMaxTokens(false));
    }
}
