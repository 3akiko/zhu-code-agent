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

class OpenAiClientTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private ProviderConfig provider(String baseUrl) {
        ProviderConfig cfg = new ProviderConfig();
        cfg.setName("openai");
        cfg.setProtocol("openai");
        cfg.setModel("gpt-4o");
        cfg.setBaseUrl(baseUrl);
        cfg.setApiKey("test-key");
        return cfg;
    }

    private static final String SSE_FULL = """
            data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

            data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}

            data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":" there"},"finish_reason":"stop"}]}

            data: {"id":"chatcmpl-1","choices":[],"usage":{"prompt_tokens":9,"completion_tokens":7,"total_tokens":16}}

            data: [DONE]

            """;

    @Test
    void streamsTextEventsAndEnd() throws Exception {
        try (MockHttpServer server = new MockHttpServer((ex, body) -> MockHttpServer.writeSse(ex, SSE_FULL))) {
            OpenAiClient client = new OpenAiClient(provider(server.baseUrl()));
            List<StreamEvent> events = StreamTestSupport.drain(
                    client.stream(new ChatRequest("sys", List.of(new Message(Role.USER, "hi")))), TIMEOUT);

            assertEquals(3, events.size());
            assertEquals(new StreamEvent.TextDelta("Hi"), events.get(0));
            assertEquals(new StreamEvent.TextDelta(" there"), events.get(1));
            assertEquals(new StreamEvent.StreamEnd("stop", 9, 7), events.get(2));
        }
    }

    @Test
    void requestBodyHasSystemFirstAndIncludeUsage() throws Exception {
        try (MockHttpServer server = new MockHttpServer((ex, body) -> MockHttpServer.writeSse(ex, SSE_FULL))) {
            OpenAiClient client = new OpenAiClient(provider(server.baseUrl()));
            StreamTestSupport.drain(client.stream(
                    new ChatRequest("sys", List.of(new Message(Role.USER, "hi"), new Message(Role.ASSISTANT, "yo")))), TIMEOUT);

            String body = server.lastRequestBody();
            assertTrue(body.contains("\"model\":\"gpt-4o\""));
            assertTrue(body.contains("\"role\":\"system\",\"content\":\"sys\""));
            assertTrue(body.contains("\"stream\":true"));
            assertTrue(body.contains("\"stream_options\":{\"include_usage\":true}"));
            // system 消息必须是第一条
            int sysIdx = body.indexOf("\"role\":\"system\"");
            int userIdx = body.indexOf("\"role\":\"user\"");
            assertTrue(sysIdx >= 0 && sysIdx < userIdx);
        }
    }

    @Test
    void httpErrorProducesErrorEvent() throws Exception {
        try (MockHttpServer server = new MockHttpServer((ex, body) -> MockHttpServer.writeStatus(ex, 500, "boom"))) {
            OpenAiClient client = new OpenAiClient(provider(server.baseUrl()));
            List<StreamEvent> events = StreamTestSupport.drain(
                    client.stream(new ChatRequest("sys", List.of())), TIMEOUT);
            assertEquals(1, events.size());
            assertInstanceOf(StreamEvent.Error.class, events.get(0));
            assertTrue(((StreamEvent.Error) events.get(0)).message().contains("500"));
        }
    }

    @Test
    void malformedSseProducesErrorEvent() throws Exception {
        try (MockHttpServer server = new MockHttpServer((ex, body) -> MockHttpServer.writeSse(ex, "data: not-json\n\n"))) {
            OpenAiClient client = new OpenAiClient(provider(server.baseUrl()));
            List<StreamEvent> events = StreamTestSupport.drain(
                    client.stream(new ChatRequest("sys", List.of())), TIMEOUT);
            assertEquals(1, events.size());
            assertInstanceOf(StreamEvent.Error.class, events.get(0));
        }
    }

    private static final String SSE_TOOL = """
            data: {"id":"1","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_file","arguments":""}}]},"finish_reason":null}]}

            data: {"id":"1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"path\\":"}}]},"finish_reason":null}]}

            data: {"id":"1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"a.txt\\"}"}}]},"finish_reason":"tool_calls"}]}

            data: {"id":"1","choices":[],"usage":{"prompt_tokens":7,"completion_tokens":5,"total_tokens":12}}

            data: [DONE]

            """;

    @Test
    void functionCallParsedToToolCallEvents() throws Exception {
        try (MockHttpServer server = new MockHttpServer((ex, body) -> MockHttpServer.writeSse(ex, SSE_TOOL))) {
            OpenAiClient client = new OpenAiClient(provider(server.baseUrl()));
            List<StreamEvent> events = StreamTestSupport.drain(
                    client.stream(new ChatRequest("sys", List.of(new Message(Role.USER, "hi")))), TIMEOUT);

            assertEquals(2, events.size());
            assertEquals(new StreamEvent.StreamEnd("tool_calls", 7, 5), events.get(1));
        }
    }

    @Test
    void requestBodyContainsToolsAndToolRoleMessages() throws Exception {
        try (MockHttpServer server = new MockHttpServer((ex, body) -> MockHttpServer.writeSse(ex, SSE_FULL))) {
            OpenAiClient client = new OpenAiClient(provider(server.baseUrl()));
            com.fasterxml.jackson.databind.node.ObjectNode schema =
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            List<Message> history = List.of(
                    new Message(Role.USER, "q"),
                    new Message(Role.ASSISTANT, List.of(
                            new ContentBlock.ToolUseBlock("call_1", "read_file", "{\"path\":\"a.txt\"}")), null, null),
                    new Message(Role.USER, List.of(
                            new ContentBlock.ToolResultBlock("call_1", "read_file", false, "ok")), null, null));
            StreamTestSupport.drain(client.stream(
                    new ChatRequest("sys", history, List.of(new ToolSpec("read_file", "读文件", schema)))), TIMEOUT);

            String body = server.lastRequestBody();
            assertTrue(body.contains("\"tools\":[{\"type\":\"function\""));
            assertTrue(body.contains("\"tool_calls\":[{\"id\":\"call_1\""));
            assertTrue(body.contains("\"role\":\"tool\",\"tool_call_id\":\"call_1\""));
        }
    }
}
