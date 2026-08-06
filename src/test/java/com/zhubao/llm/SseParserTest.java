package com.zhubao.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SseParserTest {

    @Test
    void parsesAnthropicStyleEvents() {
        String sse = """
                event: message_start
                data: {"type":"message_start"}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
                """;
        List<SseEvent> events = SseParser.parse(sse);
        assertEquals(3, events.size());
        assertEquals("message_start", events.get(0).event());
        assertTrue(events.get(0).data().contains("\"type\":\"message_start\""));
        assertEquals("content_block_delta", events.get(2).event());
        assertTrue(events.get(2).data().contains("text_delta"));
    }

    @Test
    void parsesOpenAiStyleEvents() {
        String sse = """
                data: {"id":"1","choices":[{"delta":{"role":"assistant"}}]}

                data: {"id":"1","choices":[{"delta":{"content":"Hello"}}]}

                data: [DONE]
                """;
        List<SseEvent> events = SseParser.parse(sse);
        assertEquals(3, events.size());
        assertNull(events.get(0).event(), "OpenAI 流没有 event 行");
        assertTrue(events.get(1).data().contains("Hello"));
        assertEquals("[DONE]", events.get(2).data());
    }

    @Test
    void multiLineDataJoinedWithNewline() {
        String sse = """
                data: line1
                data: line2

                """;
        List<SseEvent> events = SseParser.parse(sse);
        assertEquals(1, events.size());
        assertEquals("line1\nline2", events.get(0).data());
    }

    @Test
    void commentsIgnored() {
        String sse = """
                : this is a comment

                data: {"ok":true}

                """;
        List<SseEvent> events = SseParser.parse(sse);
        assertEquals(1, events.size());
        assertTrue(events.get(0).data().contains("ok"));
    }

    @Test
    void crlfHandled() {
        List<SseEvent> events = SseParser.parse("event: delta\r\ndata: {\"t\":1}\r\n\r\n");
        assertEquals(1, events.size());
        assertEquals("delta", events.get(0).event());
    }

    @Test
    void incrementalFeedLine() {
        SseParser parser = new SseParser();
        assertTrue(parser.feedLine("event: delta").isEmpty());
        assertTrue(parser.feedLine("data: {\"x\":1}").isEmpty());
        var event = parser.feedLine(""); // 空行触发
        assertTrue(event.isPresent());
        assertEquals("delta", event.get().event());
        assertEquals("{\"x\":1}", event.get().data());
        // 状态已复位，下一轮事件可继续解析
        assertTrue(parser.feedLine("data: next").isEmpty());
        var next = parser.feedLine("");
        assertTrue(next.isPresent());
        assertNull(next.get().event());
        assertEquals("next", next.get().data());
    }

    @Test
    void emptyOrNullTextYieldsNoEvents() {
        assertTrue(SseParser.parse("").isEmpty());
        assertTrue(SseParser.parse(null).isEmpty());
    }

    @Test
    void blankDataWithoutDataLineIgnored() {
        List<SseEvent> events = SseParser.parse("event: keepalive\n\n");
        assertTrue(events.isEmpty(), "没有 data 行的事件不应触发");
    }
}
