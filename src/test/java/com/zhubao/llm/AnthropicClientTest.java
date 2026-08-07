package com.zhubao.llm;

import com.zhubao.config.ProviderConfig;
import com.zhubao.llm.ToolSpec;
import com.zhubao.conversation.ContentBlock;
import com.zhubao.conversation.Message;
import com.zhubao.conversation.Role;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicClientTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private ProviderConfig provider(String baseUrl, boolean thinking) {
        ProviderConfig cfg = new ProviderConfig();
        cfg.setName("claude");
        cfg.setProtocol("anthropic");
        cfg.setModel("claude-sonnet-4-5");
        cfg.setBaseUrl(baseUrl);
        cfg.setApiKey("test-key");
        cfg.setThinking(thinking);
        return cfg;
    }

    private static final String SSE_FULL = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":10,"output_tokens":1}}}

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
            data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Hello"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":" world"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":1}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":12}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    private static final String SSE_MINIMAL = """
            data: {"type":"message_start","message":{"id":"m","usage":{"input_tokens":1,"output_tokens":1}}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    @Test
    void streamsThinkingAndTextEventsInOrder() throws Exception {
        try (MockHttpServer server = new MockHttpServer((ex, body) -> MockHttpServer.writeSse(ex, SSE_FULL))) {
            AnthropicClient client = new AnthropicClient(provider(server.baseUrl(), true));
            List<StreamEvent> events = StreamTestSupport.drain(
                    client.stream(new ChatRequest("sys", List.of(new Message(Role.USER, "hi")))), TIMEOUT);

            assertEquals(5, events.size());
            assertEquals(new StreamEvent.ThinkingDelta("Let me think"), events.get(0));
            assertEquals(new StreamEvent.ThinkingComplete("sig-abc"), events.get(1));
            assertEquals(new StreamEvent.TextDelta("Hello"), events.get(2));
            assertEquals(new StreamEvent.TextDelta(" world"), events.get(3));
            assertEquals(new StreamEvent.StreamEnd("end_turn", 10, 12), events.get(4));
        }
    }

    @Test
    void requestBodyContainsThinkingConfigAndEchoesSignature() throws Exception {
        try (MockHttpServer server = new MockHttpServer((ex, body) -> MockHttpServer.writeSse(ex, SSE_MINIMAL))) {
            AnthropicClient client = new AnthropicClient(provider(server.baseUrl(), true));
            List<Message> history = List.of(
                    new Message(Role.USER, "q1"),
                    new Message(Role.ASSISTANT, "a1", "thinking text", "sig-1"),
                    new Message(Role.USER, "q2"));
            StreamTestSupport.drain(client.stream(new ChatRequest("sys", history)), TIMEOUT);

            String body = server.lastRequestBody();
            assertTrue(body.contains("\"model\":\"claude-sonnet-4-5\""));
            assertTrue(body.contains("\"system\":\"sys\""));
            assertTrue(body.contains("\"stream\":true"));
            assertTrue(body.contains("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":32000}"));
            // 多轮回传：assistant 历史消息带 thinking 块与 signature
            assertTrue(body.contains("\"type\":\"thinking\""));
            assertTrue(body.contains("\"signature\":\"sig-1\""));
        }
    }

    @Test
    void noThinkingConfigWhenDisabled() throws Exception {
        try (MockHttpServer server = new MockHttpServer((ex, body) -> MockHttpServer.writeSse(ex, SSE_MINIMAL))) {
            AnthropicClient client = new AnthropicClient(provider(server.baseUrl(), false));
            StreamTestSupport.drain(client.stream(new ChatRequest("sys", List.of(new Message(Role.USER, "hi")))), TIMEOUT);
            assertFalse(server.lastRequestBody().contains("\"thinking\""));
        }
    }

    @Test
    void httpErrorProducesErrorEvent() throws Exception {
        try (MockHttpServer server = new MockHttpServer((ex, body) -> MockHttpServer.writeStatus(ex, 401, "unauthorized"))) {
            AnthropicClient client = new AnthropicClient(provider(server.baseUrl(), false));
            List<StreamEvent> events = StreamTestSupport.drain(
                    client.stream(new ChatRequest("sys", List.of())), TIMEOUT);
            assertEquals(1, events.size());
            assertInstanceOf(StreamEvent.Error.class, events.get(0));
            assertTrue(((StreamEvent.Error) events.get(0)).message().contains("401"));
        }
    }

    @Test
    void malformedSseProducesErrorEvent() throws Exception {
        try (MockHttpServer server = new MockHttpServer((ex, body) -> MockHttpServer.writeSse(ex, "data: not-json\n\n"))) {
            AnthropicClient client = new AnthropicClient(provider(server.baseUrl(), false));
            List<StreamEvent> events = StreamTestSupport.drain(
                    client.stream(new ChatRequest("sys", List.of())), TIMEOUT);
            assertEquals(1, events.size());
            assertInstanceOf(StreamEvent.Error.class, events.get(0));
        }
    }

    private static final String SSE_TOOL = """
            event: message_start
            data: {"type":"message_start","message":{"id":"m","usage":{"input_tokens":5,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"read_file","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"path\\":\\"a.txt\\"}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":8}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    @Test
    void toolUseParsedToToolCallEvent() throws Exception {
        try (MockHttpServer server = new MockHttpServer((ex, body) -> MockHttpServer.writeSse(ex, SSE_TOOL))) {
            AnthropicClient client = new AnthropicClient(provider(server.baseUrl(), false));
            List<StreamEvent> events = StreamTestSupport.drain(
                    client.stream(new ChatRequest("sys", List.of(new Message(Role.USER, "hi")))), TIMEOUT);

            assertEquals(2, events.size());
            assertEquals(new StreamEvent.StreamEnd("tool_use", 5, 8), events.get(1));
        }
    }

    @Test
    void requestBodyContainsToolsAndToolBlocks() throws Exception {
        try (MockHttpServer server = new MockHttpServer((ex, body) -> MockHttpServer.writeSse(ex, SSE_MINIMAL))) {
            AnthropicClient client = new AnthropicClient(provider(server.baseUrl(), false));
            com.fasterxml.jackson.databind.node.ObjectNode schema =
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            List<Message> history = List.of(
                    new Message(Role.USER, "q"),
                    new Message(Role.ASSISTANT, List.of(
                            new ContentBlock.ToolUseBlock("tu1", "read_file", "{\"path\":\"a.txt\"}")), null, null),
                    new Message(Role.USER, List.of(
                            new ContentBlock.ToolResultBlock("tu1", "read_file", false, "file content")), null, null));
            StreamTestSupport.drain(client.stream(
                    new ChatRequest("sys", history, List.of(new ToolSpec("read_file", "读文件", schema)))), TIMEOUT);

            String body = server.lastRequestBody();
            assertTrue(body.contains("\"tools\":[{\"name\":\"read_file\""));
            assertTrue(body.contains("\"type\":\"tool_use\",\"id\":\"tu1\",\"name\":\"read_file\""));
            assertTrue(body.contains("\"type\":\"tool_result\",\"tool_use_id\":\"tu1\""));
        }
    }
}
