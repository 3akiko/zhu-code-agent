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
 * </ul>
 */
public class ProviderConfig {

    private String name;
    private String protocol;
    private String model;
    private String baseUrl;
    private String apiKey;
    private boolean thinking;

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

    @Override
    public String toString() {
        // 刻意不打印 apiKey，避免密钥泄漏到日志/异常（N4 安全要求）
        return "ProviderConfig{name='" + name + "', protocol='" + protocol + "', model='" + model + "'}";
    }
}
