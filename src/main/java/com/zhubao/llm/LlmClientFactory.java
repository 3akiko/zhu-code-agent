package com.zhubao.llm;

import com.zhubao.config.ProviderConfig;

/**
 * Provider 工厂（spec F6）：按 protocol 创建对应客户端。
 * 新增后端 = 新增一个实现类 + 这里加一个分支，调用方（TUI）不变。
 */
public final class LlmClientFactory {

    private LlmClientFactory() {
    }

    public static LlmClient create(ProviderConfig config) {
        return switch (config.getProtocol()) {
            case "anthropic" -> new AnthropicClient(config);
            case "openai" -> new OpenAiClient(config);
            default -> throw new LlmException("不支持的协议: " + config.getProtocol());
        };
    }
}
