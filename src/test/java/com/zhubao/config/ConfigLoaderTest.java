package com.zhubao.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 配置加载单测：六字段解析、api_key 三级解析、校验、安全脱敏。
 */
class ConfigLoaderTest {

    @TempDir
    Path tmp;

    private Path writeConfig(String content) throws IOException {
        Path p = tmp.resolve("config.yml");
        Files.writeString(p, content);
        return p;
    }

    @Test
    void sixFieldsParsed() throws Exception {
        Path p = writeConfig("""
                providers:
                  - name: claude
                    protocol: anthropic
                    model: claude-sonnet-4-5
                    base_url: https://api.anthropic.com
                    api_key: sk-ant-test
                    thinking: true
                  - name: openai
                    protocol: openai
                    model: gpt-4o
                    base_url: https://api.openai.com
                    api_key: sk-openai-test
                """);
        AppConfig cfg = ConfigLoader.load(p.toString(), Map.of());
        assertEquals(2, cfg.providers().size());

        ProviderConfig claude = cfg.providers().get(0);
        assertEquals("claude", claude.getName());
        assertEquals("anthropic", claude.getProtocol());
        assertEquals("claude-sonnet-4-5", claude.getModel());
        assertEquals("https://api.anthropic.com", claude.getBaseUrl());
        assertEquals("sk-ant-test", claude.getApiKey());
        assertTrue(claude.isThinking());

        ProviderConfig openai = cfg.providers().get(1);
        assertEquals("openai", openai.getName());
        assertEquals("sk-openai-test", openai.getApiKey());
        assertFalse(openai.isThinking());
    }

    @Test
    void envVarReferenceExpanded() throws Exception {
        Path p = writeConfig("""
                providers:
                  - name: claude
                    protocol: anthropic
                    model: claude-sonnet-4-5
                    api_key: ${MY_TEST_KEY}
                """);
        AppConfig cfg = ConfigLoader.load(p.toString(), Map.of("MY_TEST_KEY", "env-secret"));
        assertEquals("env-secret", cfg.providers().get(0).getApiKey());
    }

    @Test
    void envVarReferenceMissingThrowsReadableError() throws Exception {
        Path p = writeConfig("""
                providers:
                  - name: claude
                    protocol: anthropic
                    model: claude-sonnet-4-5
                    api_key: ${MISSING_ENV_XXX}
                """);
        ConfigException ex = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(p.toString(), Map.of()));
        assertTrue(ex.getMessage().contains("MISSING_ENV_XXX"), "错误消息应包含缺失的环境变量名");
    }

    @Test
    void blankApiKeyFallsBackToConventionEnv() throws Exception {
        Path p = writeConfig("""
                providers:
                  - name: openai
                    protocol: openai
                    model: gpt-4o
                    api_key:
                """);
        AppConfig cfg = ConfigLoader.load(p.toString(), Map.of("OPENAI_API_KEY", "fallback-secret"));
        assertEquals("fallback-secret", cfg.providers().get(0).getApiKey());
    }

    @Test
    void blankApiKeyAndNoEnvLeavesBlank() throws Exception {
        Path p = writeConfig("""
                providers:
                  - name: claude
                    protocol: anthropic
                    model: claude-sonnet-4-5
                """);
        AppConfig cfg = ConfigLoader.load(p.toString(), Map.of());
        assertEquals("", cfg.providers().get(0).getApiKey());
    }

    @Test
    void blankBaseUrlUsesProtocolDefault() throws Exception {
        Path p = writeConfig("""
                providers:
                  - name: claude
                    protocol: anthropic
                    model: claude-sonnet-4-5
                """);
        AppConfig cfg = ConfigLoader.load(p.toString(), Map.of());
        assertEquals("https://api.anthropic.com", cfg.providers().get(0).getBaseUrl());
    }

    @Test
    void emptyProvidersThrows() throws Exception {
        Path p = writeConfig("providers: []");
        assertThrows(ConfigException.class, () -> ConfigLoader.load(p.toString(), Map.of()));
    }

    @Test
    void invalidProtocolThrows() throws Exception {
        Path p = writeConfig("""
                providers:
                  - name: x
                    protocol: unknown
                    model: m
                """);
        ConfigException ex = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(p.toString(), Map.of()));
        assertTrue(ex.getMessage().contains("unknown"));
    }

    @Test
    void duplicateNameThrows() throws Exception {
        Path p = writeConfig("""
                providers:
                  - name: dup
                    protocol: openai
                    model: gpt-4o
                  - name: dup
                    protocol: anthropic
                    model: claude-sonnet-4-5
                """);
        assertThrows(ConfigException.class, () -> ConfigLoader.load(p.toString(), Map.of()));
    }

    @Test
    void missingModelThrows() throws Exception {
        Path p = writeConfig("""
                providers:
                  - name: x
                    protocol: openai
                """);
        assertThrows(ConfigException.class, () -> ConfigLoader.load(p.toString(), Map.of()));
    }

    @Test
    void errorMessageNeverContainsApiKey() throws Exception {
        Path p = writeConfig("""
                providers:
                  - name: x
                    protocol: bad-protocol
                    model: m
                    api_key: TOP_SECRET_123
                """);
        ConfigException ex = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(p.toString(), Map.of()));
        assertFalse(ex.getMessage().contains("TOP_SECRET_123"), "异常消息不得包含 api_key");
    }

    @Test
    void configPathFromEnvUsed() throws Exception {
        Path p = writeConfig("""
                providers:
                  - name: claude
                    protocol: anthropic
                    model: claude-sonnet-4-5
                """);
        AppConfig cfg = ConfigLoader.load(null, Map.of(ConfigLoader.CONFIG_PATH_ENV, p.toString()));
        assertEquals(1, cfg.providers().size());
    }

    @Test
    void missingFileThrowsReadable() {
        ConfigException ex = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(tmp.resolve("nope.yml").toString(), Map.of()));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    void defaultSessionsDirUnderHome() {
        Path dir = AppConfig.defaultSessionsDir();
        assertTrue(dir.toString().contains(".zhu-code-agent"));
        assertTrue(dir.endsWith("sessions"));
    }

    // ── M2：tool.max_calls_per_turn / ui.tool_preview_lines ──────────────

    @Test
    void toolAndUiDefaultsApplied() throws Exception {
        Path p = writeConfig("""
                providers:
                  - name: openai
                    protocol: openai
                    model: gpt-4o
                """);
        AppConfig cfg = ConfigLoader.load(p.toString(), Map.of());
        assertEquals(AppConfig.DEFAULT_TOOL_MAX_CALLS_PER_TURN, cfg.toolMaxCallsPerTurn());
        assertEquals(AppConfig.DEFAULT_UI_TOOL_PREVIEW_LINES, cfg.uiToolPreviewLines());
        assertEquals(AppConfig.DEFAULT_UI_DIFF_MAX_LINES, cfg.uiDiffMaxLines());
    }

    @Test
    void toolAndUiCustomValuesBound() throws Exception {
        Path p = writeConfig("""
                providers:
                  - name: openai
                    protocol: openai
                    model: gpt-4o
                tool:
                  max_calls_per_turn: 30
                ui:
                  tool_preview_lines: 8
                  diff_max_lines: 300
                """);
        AppConfig cfg = ConfigLoader.load(p.toString(), Map.of());
        assertEquals(30, cfg.toolMaxCallsPerTurn());
        assertEquals(8, cfg.uiToolPreviewLines());
        assertEquals(300, cfg.uiDiffMaxLines());
    }

    @Test
    void invalidToolValueFallsBackToDefault() throws Exception {
        Path p = writeConfig("""
                providers:
                  - name: openai
                    protocol: openai
                    model: gpt-4o
                tool:
                  max_calls_per_turn: -5
                """);
        AppConfig cfg = ConfigLoader.load(p.toString(), Map.of());
        assertEquals(AppConfig.DEFAULT_TOOL_MAX_CALLS_PER_TURN, cfg.toolMaxCallsPerTurn());
    }
}