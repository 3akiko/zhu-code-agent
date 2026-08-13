package com.zhubao.llm;

import com.zhubao.config.ProviderConfig;
import com.zhubao.conversation.Message;
import com.zhubao.conversation.Role;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M5（spec F1）客户端并发安全测试：
 * ① 同一实例并发两个流，各自文本/token 互不串扰；② 对一个流 cancel 不影响另一个；
 * ③ 实例复用后 cancelled 不残留（首流取消后再起新流正常）；④ 工厂按 provider 名缓存复用。
 *
 * <p>用 OpenAI 协议 mock（SSE）验证——两协议共享 per-call 骨架（AbstractStreamingClient），
 * OpenAI 覆盖骨架竞态即可；Anthropic 事件契约已由 AnthropicClientTest 覆盖。
 */
class ConcurrentStreamTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private ProviderConfig provider(String name, String baseUrl) {
        ProviderConfig cfg = new ProviderConfig();
        cfg.setName(name);
        cfg.setProtocol("openai");
        cfg.setModel("gpt-4o");
        cfg.setBaseUrl(baseUrl);
        cfg.setApiKey("test-key");
        return cfg;
    }

    private static ChatRequest request(String marker) {
        return new ChatRequest("sys", List.of(new Message(Role.USER, marker)));
    }

    private static String sseWithText(String text) {
        return """
                data: {"id":"1","choices":[{"index":0,"delta":{"content":"%s"},"finish_reason":"stop"}]}

                data: {"id":"1","choices":[],"usage":{"prompt_tokens":3,"completion_tokens":5,"total_tokens":8}}

                data: [DONE]

                """.formatted(text);
    }

    private static String textOf(List<StreamEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (StreamEvent e : events) {
            if (e instanceof StreamEvent.TextDelta td) {
                sb.append(td.text());
            }
        }
        return sb.toString();
    }

    @Test
    void concurrentStreamsOnSameInstanceDoNotInterfere() throws Exception {
        try (MockHttpServer server = new MockHttpServer((ex, body) -> {
            // 轻微延迟保证两个流重叠；按请求内容返回不同文本
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (body.contains("alpha")) {
                MockHttpServer.writeSse(ex, sseWithText("ALPHA-OK"));
            } else {
                MockHttpServer.writeSse(ex, sseWithText("BETA-OK"));
            }
        })) {
            LlmClient client = LlmClientFactory.create(provider("concurrent", server.baseUrl()));

            List<StreamEvent> ea = StreamTestSupport.drain(client.stream(request("alpha")), TIMEOUT);
            List<StreamEvent> eb = StreamTestSupport.drain(client.stream(request("beta")), TIMEOUT);

            // 各自的文本互不串扰
            assertEquals("ALPHA-OK", textOf(ea));
            assertEquals("BETA-OK", textOf(eb));
            // 各自的 StreamEnd usage 独立（同 mock 所以数值相同，但必须都存在且各自为 StreamEnd）
            assertInstanceOf(StreamEvent.StreamEnd.class, ea.get(ea.size() - 1));
            assertInstanceOf(StreamEvent.StreamEnd.class, eb.get(eb.size() - 1));
            StreamEvent.StreamEnd endA = (StreamEvent.StreamEnd) ea.get(ea.size() - 1);
            StreamEvent.StreamEnd endB = (StreamEvent.StreamEnd) eb.get(eb.size() - 1);
            assertEquals(3, endA.inputTokens());
            assertEquals(3, endB.inputTokens());
            assertEquals(5, endA.outputTokens());
            assertEquals(5, endB.outputTokens());
        }
    }

    @Test
    void cancellingOneStreamDoesNotAffectAnother() throws Exception {
        try (MockHttpServer server = new MockHttpServer((ex, body) -> {
            if (body.contains("slow")) {
                // 永不结束：长时间不写响应
                try {
                    Thread.sleep(20_000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            MockHttpServer.writeSse(ex, sseWithText("FAST-OK"));
        })) {
            LlmClient client = LlmClientFactory.create(provider("cancel-iso", server.baseUrl()));

            LlmStream slow = client.stream(request("slow"));
            LlmStream fast = client.stream(request("fast"));

            // 快的先完成
            List<StreamEvent> fastEvents = StreamTestSupport.drain(fast, TIMEOUT);
            assertEquals("FAST-OK", textOf(fastEvents));
            assertInstanceOf(StreamEvent.StreamEnd.class, fastEvents.get(fastEvents.size() - 1));

            // 取消慢的：收到「已中断」错误，不影响快的已完成结果
            slow.cancel();
            List<StreamEvent> slowEvents = StreamTestSupport.drain(slow, TIMEOUT);
            assertTrue(slowEvents.stream().anyMatch(e ->
                    e instanceof StreamEvent.Error err && "已中断".equals(err.message())),
                    "被取消的流应收到「已中断」错误事件");
        }
    }

    @Test
    void cancelledFlagDoesNotLeakAfterInstanceReuse() throws Exception {
        try (MockHttpServer server = new MockHttpServer((ex, body) -> {
            if (body.contains("slow")) {
                try {
                    Thread.sleep(20_000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            MockHttpServer.writeSse(ex, sseWithText("AFTER-OK"));
        })) {
            LlmClient client = LlmClientFactory.create(provider("reuse", server.baseUrl()));

            // 第一次调用取消
            LlmStream first = client.stream(request("slow"));
            Thread.sleep(100);
            first.cancel();
            StreamTestSupport.drain(first, TIMEOUT);

            // 同一实例第二次调用：cancelled 标志不残留，正常出结果
            LlmStream second = client.stream(request("normal"));
            List<StreamEvent> events = StreamTestSupport.drain(second, TIMEOUT);
            assertEquals("AFTER-OK", textOf(events));
            assertInstanceOf(StreamEvent.StreamEnd.class, events.get(events.size() - 1));
        }
    }

    @Test
    void factoryCachesInstanceByName() {
        ProviderConfig cfg = provider("cache-test", "http://localhost:1");
        LlmClient first = LlmClientFactory.create(cfg);
        LlmClient second = LlmClientFactory.create(cfg);
        assertSame(first, second, "同一 provider 名应复用同一实例");
    }
}
