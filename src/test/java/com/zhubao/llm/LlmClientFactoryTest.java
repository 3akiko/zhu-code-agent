package com.zhubao.llm;

import com.zhubao.config.ProviderConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LlmClientFactoryTest {

    /**
     * M5：工厂按 provider 名缓存复用单例——测试各用例用不同 name，避免同名命中缓存
     * （真实语义：配置加载时 provider 名唯一校验）。
     */
    private ProviderConfig provider(String name, String protocol) {
        ProviderConfig cfg = new ProviderConfig();
        cfg.setName(name);
        cfg.setProtocol(protocol);
        cfg.setModel("m");
        cfg.setBaseUrl("http://localhost:1");
        cfg.setApiKey("k");
        return cfg;
    }

    @Test
    void createsAnthropicClientByProtocol() {
        assertInstanceOf(AnthropicClient.class, LlmClientFactory.create(provider("p-anthropic", "anthropic")));
    }

    @Test
    void createsOpenAiClientByProtocol() {
        assertInstanceOf(OpenAiClient.class, LlmClientFactory.create(provider("p-openai", "openai")));
    }

    @Test
    void unknownProtocolThrows() {
        assertThrows(LlmException.class, () -> LlmClientFactory.create(provider("p-unknown", "unknown")));
    }
}
