package com.zhubao.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProviderConfig 协议感知占用基数（M4 变更控制 2026-08-11）：
 * Anthropic input_tokens 不含缓存命中需 + cacheRead；OpenAI prompt_tokens 已含缓存不重复计。
 */
class ProviderConfigTest {

    @Test
    void anthropicOccupancyAddsCacheRead() {
        ProviderConfig cfg = new ProviderConfig();
        cfg.setProtocol("anthropic");
        // 实测口径：input=42（未缓存）+ cacheRead=768（命中）= 810 真实占用
        assertEquals(810, cfg.occupancyBasis(42, 768));
    }

    @Test
    void anthropicWithoutCacheUnchanged() {
        ProviderConfig cfg = new ProviderConfig();
        cfg.setProtocol("anthropic");
        assertEquals(321, cfg.occupancyBasis(321, 0));
    }

    @Test
    void openaiOccupancyKeepsPromptTokensIncludingCache() {
        ProviderConfig cfg = new ProviderConfig();
        cfg.setProtocol("openai");
        // prompt_tokens=723 已含缓存命中 640，不能把 cacheRead 再加一遍
        assertEquals(723, cfg.occupancyBasis(723, 640));
    }

    @Test
    void openaiCacheReadIgnored() {
        ProviderConfig cfg = new ProviderConfig();
        cfg.setProtocol("openai");
        assertEquals(28, cfg.occupancyBasis(28, 0));
    }

    @Test
    void negativeCacheReadClamped() {
        ProviderConfig cfg = new ProviderConfig();
        cfg.setProtocol("anthropic");
        assertEquals(100, cfg.occupancyBasis(100, -5));
    }
}
