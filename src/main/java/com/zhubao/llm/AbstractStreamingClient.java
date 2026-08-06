package com.zhubao.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhubao.config.ProviderConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 流式客户端的公共骨架（模板方法模式）：
 *
 * <pre>
 * 后台线程：buildRequest → HttpClient 发送 → 按行喂 SseParser →
 *           isStreamEnd 判定结束 → handleEvent 协议映射 → 事件入队
 * </pre>
 *
 * 统一错误处理：非 2xx 与任何异常都转为 {@link StreamEvent.Error} 入队，
 * 保证调用方不会永久阻塞；异常消息不含密钥（N4）。
 */
abstract class AbstractStreamingClient implements LlmClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final int ERROR_BODY_MAX_CHARS = 300;

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    protected final ProviderConfig config;
    private final HttpClient httpClient;

    AbstractStreamingClient(ProviderConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public final BlockingQueue<StreamEvent> stream(ChatRequest request) {
        BlockingQueue<StreamEvent> queue = new LinkedBlockingQueue<>();
        // M1 为单线程对话，一次只有一个流；daemon 线程避免阻塞退出
        Thread worker = new Thread(() -> runStream(request, queue), "llm-stream-" + config.getName());
        worker.setDaemon(true);
        worker.start();
        return queue;
    }

    private void runStream(ChatRequest request, BlockingQueue<StreamEvent> queue) {
        onStreamStart();
        try {
            HttpRequest httpRequest = buildRequest(request);
            HttpResponse<InputStream> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                put(queue, new StreamEvent.Error(
                        "HTTP " + response.statusCode() + ": " + readBody(response, ERROR_BODY_MAX_CHARS)));
                return;
            }
            boolean ended = false;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                SseParser parser = new SseParser();
                String line;
                while ((line = reader.readLine()) != null) {
                    Optional<SseEvent> eventOpt = parser.feedLine(line);
                    if (eventOpt.isEmpty()) {
                        continue;
                    }
                    SseEvent sse = eventOpt.get();
                    if (isStreamEnd(sse)) {
                        handleEvent(sse, queue); // 子类在此发 StreamEnd（含 usage）
                        ended = true;
                        break;
                    }
                    handleEvent(sse, queue);
                }
            }
            if (!ended) {
                // 流 EOF 但未收到协议结束事件：兜底，保证调用方不永久阻塞
                onStreamEof(queue);
            }
        } catch (Exception e) {
            put(queue, new StreamEvent.Error("请求失败: " + safeMessage(e)));
        }
    }

    /** 每个流开始时重置累积状态（子类可覆盖） */
    protected void onStreamStart() {
    }

    /** 流 EOF 且未收到结束事件时的兜底（默认发 StreamEnd） */
    protected void onStreamEof(BlockingQueue<StreamEvent> queue) {
        put(queue, new StreamEvent.StreamEnd("eof", 0, 0));
    }

    /** 构造 HTTP 请求（协议相关） */
    protected abstract HttpRequest buildRequest(ChatRequest request);

    /** 判定该 SSE 事件是否为流结束标志 */
    protected abstract boolean isStreamEnd(SseEvent sse);

    /** 把一条 SSE 事件映射为 StreamEvent 并入队 */
    protected abstract void handleEvent(SseEvent sse, BlockingQueue<StreamEvent> queue);

    protected String readBody(HttpResponse<InputStream> response, int maxChars) {
        try (InputStream in = response.body()) {
            byte[] buf = in.readNBytes(maxChars);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    protected static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new LlmException("JSON 序列化失败", e);
        }
    }

    protected static void put(BlockingQueue<StreamEvent> queue, StreamEvent event) {
        try {
            queue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String safeMessage(Exception e) {
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
    }
}
