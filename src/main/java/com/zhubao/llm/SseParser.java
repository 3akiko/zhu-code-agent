package com.zhubao.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 极简 SSE 解析器（SSE 规范子集，够用即可）。
 *
 * <p>支持的字段行：{@code event:}、{@code data:}；空行触发一个完整事件；
 * {@code :} 开头为注释忽略；其他字段行（id/retry 等）忽略；兼容 CRLF 换行；
 * 多行 data 以 {@code \n} 连接。
 *
 * <p>用法：HTTP 流按行 {@link #feedLine(String)}，返回非空 Optional 即一个完整事件。
 */
public final class SseParser {

    private String currentEvent;
    private final StringBuilder currentData = new StringBuilder();
    private boolean dataSeen;

    /**
     * 解析整段 SSE 文本（按空行切分事件），供单测与调试。
     */
    public static List<SseEvent> parse(String text) {
        SseParser parser = new SseParser();
        List<SseEvent> events = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return events;
        }
        for (String rawLine : text.split("\n", -1)) {
            parser.feedLine(rawLine).ifPresent(events::add);
        }
        return events;
    }

    /**
     * 逐行喂入解析器。
     *
     * @param line 一行（不含换行符；可带结尾 \r）
     * @return 空行触发完整事件时返回该事件，否则 empty
     */
    public Optional<SseEvent> feedLine(String line) {
        String l = (line != null && line.endsWith("\r")) ? line.substring(0, line.length() - 1) : line;
        if (l == null || l.isEmpty()) {
            return flush();
        }
        if (l.startsWith(":")) {
            // 注释行，忽略
            return Optional.empty();
        }
        if (l.startsWith("event:")) {
            currentEvent = l.substring("event:".length()).trim();
            return Optional.empty();
        }
        if (l.startsWith("data:")) {
            String d = l.substring("data:".length());
            if (d.startsWith(" ")) {
                d = d.substring(1);
            }
            if (dataSeen) {
                currentData.append('\n');
            }
            currentData.append(d);
            dataSeen = true;
            return Optional.empty();
        }
        // 其他字段（id、retry 等）M1 不关心
        return Optional.empty();
    }

    private Optional<SseEvent> flush() {
        if (!dataSeen) {
            return Optional.empty();
        }
        SseEvent event = new SseEvent(currentEvent, currentData.toString());
        currentEvent = null;
        currentData.setLength(0);
        dataSeen = false;
        return Optional.of(event);
    }
}
