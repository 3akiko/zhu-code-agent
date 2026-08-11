package com.zhubao.config;

/**
 * 单个 LLM 供应商配置（对应 YAML providers 列表中的一项）。
 *
 * 六个字段：
 * <ul>
 *   <li>name     —— 供应商标识名，用于区分多个配置与会话恢复</li>
 *   <li>protocol —— 协议类型：anthropic | openai</li>
 *   <li>model    —— 模型名</li>
 *   <li>baseUrl  —— 请求基础地址（为空时按协议取默认值，由 ConfigLoader 填充）</li>
 *   <li>apiKey   —— 认证密钥（支持直接值 / ${ENV_VAR} / 环境变量回退，由 ConfigLoader 解析）</li>
 *   <li>thinking —— 是否启用扩展思考（可选，默认 false）</li>
 *   <li>contextWindow —— 上下文窗口 token 数（可选；未配置按 {@link LlmLimits} 模型表/默认）</li>
 *   <li>maxTokens —— 单次输出上限 token 数（可选；未配置按 {@link LlmLimits} 模型表/默认）</li>
 *   <li>promptCache —— 是否发送 Anthropic cache_control 断点（可选，默认 true；
 *       兼容端点不识别 cache_control 时设 false，M4 review-P2）</li>
 * </ul>
 */
public class ProviderConfig {

    private String name;
    private String protocol;
    private String model;
    private String baseUrl;
    private String apiKey;
    private boolean thinking;
    private Integer contextWindow;
    private Integer maxTokens;
    private Boolean promptCache;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public boolean isThinking() {
        return thinking;
    }

    public void setThinking(boolean thinking) {
        this.thinking = thinking;
    }

    public Integer getContextWindow() {
        return contextWindow;
    }

    public void setContextWindow(Integer contextWindow) {
        this.contextWindow = contextWindow;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Boolean getPromptCache() {
        return promptCache;
    }

    public void setPromptCache(Boolean promptCache) {
        this.promptCache = promptCache;
    }

    /** 是否启用 prompt 缓存断点（M4，spec F4）：显式配置 ?? 默认 true */
    public boolean effectivePromptCache() {
        return promptCache == null || promptCache;
    }

    /** 生效的上下文窗口：显式配置 ?? LlmLimits 模型表 ?? 默认 32K（M4，spec F1/F2 分母） */
    public int effectiveContextWindow() {
        if (contextWindow != null && contextWindow > 0) {
            return contextWindow;
        }
        LlmLimits.ModelLimit limit = LlmLimits.byModel(model);
        return limit != null ? limit.contextWindow() : LlmLimits.defaultContextWindow();
    }

    /** 生效的输出上限：显式配置 ?? 模型表（thinking/plain 分列）?? 64000/8192（M4，spec F7） */
    public int effectiveMaxTokens(boolean thinking) {
        if (maxTokens != null && maxTokens > 0) {
            return maxTokens;
        }
        LlmLimits.ModelLimit limit = LlmLimits.byModel(model);
        if (limit != null) {
            return thinking ? limit.maxThinking() : limit.maxPlain();
        }
        return thinking ? LlmLimits.DEFAULT_MAX_THINKING : LlmLimits.DEFAULT_MAX_PLAIN;
    }

    /**
     * 上下文占用基数（M4 变更控制 2026-08-11）：按协议口径折算「本次请求真正占用的窗口」。
     *
     * <p>Anthropic 协议（含 DeepSeek /anthropic 兼容端点与真实 Claude）：{@code input_tokens}
     * 只含未缓存部分，缓存命中单独在 {@code cache_read_input_tokens}，需相加才是真实占用
     * （实测：第 2 轮 input=42 + cacheRead=768 = 810）。
     * OpenAI 协议：{@code prompt_tokens} 官方语义即「缓存命中 + 未命中」已含缓存，
     * 直接取 inputTokens，避免把缓存命中重复计入。
     */
    public int occupancyBasis(int inputTokens, int cacheReadTokens) {
        if ("anthropic".equalsIgnoreCase(protocol)) {
            return inputTokens + Math.max(0, cacheReadTokens);
        }
        return inputTokens;
    }

    @Override
    public String toString() {
        // 刻意不打印 apiKey，避免密钥泄漏到日志/异常（N4 安全要求）
        return "ProviderConfig{name='" + name + "', protocol='" + protocol + "', model='" + model + "'}";
    }
}
