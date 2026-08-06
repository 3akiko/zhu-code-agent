package com.zhubao.llm;

import com.zhubao.config.ProviderConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LlmClientFactoryTest {

    private ProviderConfig provider(String protocol) {
        ProviderConfig cfg = new ProviderConfig();
        cfg.setName("p");
        cfg.setProtocol(protocol);
        cfg.setModel("m");
        cfg.setBaseUrl("http://localhost:1");
        cfg.setApiKey("k");
        return cfg;
    }

    @Test
    void createsAnthropicClientByProtocol() {
        assertInstanceOf(AnthropicClient.class, LlmClientFactory.create(provider("anthropic")));
    }

    @Test
    void createsOpenAiClientByProtocol() {
        assertInstanceOf(OpenAiClient.class, LlmClientFactory.create(provider("openai")));
    }

    @Test
    void unknownProtocolThrows() {
        assertThrows(LlmException.class, () -> LlmClientFactory.create(provider("unknown")));
    }
}
