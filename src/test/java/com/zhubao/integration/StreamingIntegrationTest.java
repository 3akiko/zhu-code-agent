package com.zhubao.integration;

import com.zhubao.config.ProviderConfig;
import com.zhubao.conversation.Conversation;
import com.zhubao.conversation.Message;
import com.zhubao.conversation.Role;
import com.zhubao.llm.LlmClientFactory;
import com.zhubao.llm.MockHttpServer;
import com.zhubao.llm.StreamEvent;
import com.zhubao.session.Session;
import com.zhubao.session.SessionMeta;
import com.zhubao.session.SessionStore;
import com.zhubao.tui.TurnRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端集成测试（spec AC10 / checklist E1）：
 * mock LLM 服务器 + 真实 LlmClient + TurnRunner + SessionStore，
 * 覆盖「流式回复 → 多轮 → 会话落盘/恢复」全链路，无需真实密钥。
 */
class StreamingIntegrationTest {

    @TempDir
    Path tmp;

    private static final String OPENAI_SSE = """
            data: {"id":"1","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

            data: {"id":"1","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}

            data: {"id":"1","choices":[{"index":0,"delta":{"content":" there"},"finish_reason":"stop"}]}

            data: {"id":"1","choices":[],"usage":{"prompt_tokens":9,"completion_tokens":7,"total_tokens":16}}

            data: [DONE]

            """;

    private static final String ANTHROPIC_SSE = """
            event: message_start
            data: {"type":"message_start","message":{"id":"m","usage":{"input_tokens":10,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Let me think"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-abc"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Hello world"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":1}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":12}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    private ProviderConfig openAiProvider(String baseUrl) {
        ProviderConfig cfg = new ProviderConfig();
        cfg.setName("openai");
        cfg.setProtocol("openai");
        cfg.setModel("gpt-4o");
        cfg.setBaseUrl(baseUrl);
        cfg.setApiKey("test-key");
        return cfg;
    }

    private ProviderConfig anthropicProvider(String baseUrl) {
        ProviderConfig cfg = new ProviderConfig();
        cfg.setName("claude");
        cfg.setProtocol("anthropic");
        cfg.setModel("claude-sonnet-4-5");
        cfg.setBaseUrl(baseUrl);
        cfg.setApiKey("test-key");
        cfg.setThinking(true);
        return cfg;
    }

    /** E1 核心场景：新会话 → 流式回复 → 落盘 → 恢复，多轮上下文回传 */
    @Test
    void openAiTurnStreamsSavesAndRestores() throws Exception {
        try (MockHttpServer server = new MockHttpServer((ex, body) -> MockHttpServer.writeSse(ex, OPENAI_SSE))) {
            Conversation conversation = new Conversation();
            List<StreamEvent> rendered = new ArrayList<>();
            TurnRunner.Result result = TurnRunner.run(
                    LlmClientFactory.create(openAiProvider(server.baseUrl())),
                    conversation, "你好", rendered::add);

            // 流式正文正确、无错误
            assertFalse(result.error(), result.errorMessage());
            assertEquals("Hi there", result.text());
            assertEquals("", result.thinking());
            assertEquals("stop", result.stopReason());
            assertEquals(9, result.inputTokens());
            assertEquals(7, result.outputTokens());
            // 渲染回调收到增量事件（TextDelta）
            assertTrue(rendered.stream().anyMatch(e -> e instanceof StreamEvent.TextDelta));

            // 助手消息入会话 + 落盘
            conversation.addAssistant(result.text(), result.thinking(), result.signature());
            SessionStore store = new SessionStore(tmp);
            Instant now = Instant.now();
            SessionMeta meta = new SessionMeta("s1", now, now, conversation.previewTitle(),
                    conversation.messageCount(), new com.zhubao.session.ProviderSnapshot(
                    "openai", "openai", "gpt-4o", server.baseUrl()));
            store.save(new Session(meta, conversation.getMessages()));
            assertEquals(2, store.load("s1").orElseThrow().getMessages().size());

            // 第二轮：恢复后继续，旧消息随请求回传
            TurnRunner.run(LlmClientFactory.create(openAiProvider(server.baseUrl())),
                    conversation, "我刚才问的什么", null);
            assertEquals(3, conversation.messageCount());
            String secondBody = server.capturedBodies().get(1);
            assertTrue(secondBody.contains("\"role\":\"assistant\""));
            assertTrue(secondBody.contains("Hi there"));
        }
    }

    /** E4 thinking：灰字思考与正文分离，signature 捕获，可落盘恢复 */
    @Test
    void anthropicThinkingSeparatedAndPersisted() throws Exception {
        try (MockHttpServer server = new MockHttpServer((ex, body) -> MockHttpServer.writeSse(ex, ANTHROPIC_SSE))) {
            Conversation conversation = new Conversation();
            TurnRunner.Result result = TurnRunner.run(
                    LlmClientFactory.create(anthropicProvider(server.baseUrl())),
                    conversation, "分析一下", null);

            assertFalse(result.error(), result.errorMessage());
            assertEquals("Hello world", result.text());
            assertEquals("Let me think", result.thinking());
            assertEquals("sig-abc", result.signature());
            assertFalse(result.text().contains("Let me think"), "正文不得包含思考内容");

            // 思考内容与 signature 随会话落盘
            conversation.addAssistant(result.text(), result.thinking(), result.signature());
            SessionStore store = new SessionStore(tmp);
            SessionMeta meta = new SessionMeta("s2", Instant.now(), Instant.now(),
                    conversation.previewTitle(), conversation.messageCount(),
                    new com.zhubao.session.ProviderSnapshot("claude", "anthropic", "claude-sonnet-4-5", server.baseUrl()));
            store.save(new Session(meta, conversation.getMessages()));

            Message assistant = store.load("s2").orElseThrow().getMessages().get(1);
            assertEquals("Let me think", assistant.getThinking());
            assertEquals("sig-abc", assistant.getThinkingSignature());
        }
    }

    /** E2 边界：HTTP 500 → Error 事件，进程不崩溃，会话不追加助手消息 */
    @Test
    void httpErrorYieldsErrorResultWithoutAssistantMessage() throws Exception {
        try (MockHttpServer server = new MockHttpServer((ex, body) -> MockHttpServer.writeStatus(ex, 500, "boom"))) {
            Conversation conversation = new Conversation();
            TurnRunner.Result result = TurnRunner.run(
                    LlmClientFactory.create(openAiProvider(server.baseUrl())),
                    conversation, "触发错误", null);

            assertTrue(result.error());
            assertTrue(result.errorMessage().contains("500"));
            assertEquals(1, conversation.messageCount(), "出错时只保留用户消息，不追加助手消息");
        }
    }

    /** 多轮会话标题与消息数在保存时正确刷新 */
    @Test
    void sessionMetaRefreshedOnSave() throws Exception {
        try (MockHttpServer server = new MockHttpServer((ex, body) -> MockHttpServer.writeSse(ex, OPENAI_SSE))) {
            Conversation conversation = new Conversation();
            TurnRunner.run(LlmClientFactory.create(openAiProvider(server.baseUrl())), conversation, "我的第一个问题", null);
            conversation.addAssistant("回答", "", null);

            SessionStore store = new SessionStore(tmp);
            SessionMeta meta = new SessionMeta("s3", Instant.now(), Instant.now(),
                    conversation.previewTitle(), conversation.messageCount(),
                    new com.zhubao.session.ProviderSnapshot("openai", "openai", "gpt-4o", server.baseUrl()));
            store.save(new Session(meta, conversation.getMessages()));

            assertEquals("我的第一个问题", store.list().get(0).title());
            assertEquals(2, store.list().get(0).messageCount());
        }
    }
}
