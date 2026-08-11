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
    // M4 review-P3：流状态为实例字段且 cancelled 永不复位——当前仅「每轮新建 client + 单流串行」下安全；
    // M5 并发/复用 client 前须重构为 per-call 状态持有者（TODO 已记 M5 ① 前置）。
    /** 当前流的响应体（供 cancel 关闭以打断阻塞读取；M4，spec F5） */
    private volatile java.io.InputStream activeBody;
    /** 当前流是否已被取消（幂等） */
    private final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);

    AbstractStreamingClient(ProviderConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public final LlmStream stream(ChatRequest request) {
        BlockingQueue<StreamEvent> queue = new LinkedBlockingQueue<>();
        // M1 为单线程对话，一次只有一个流；daemon 线程避免阻塞退出
        Thread worker = new Thread(() -> runStream(request, queue), "llm-stream-" + config.getName());
        worker.setDaemon(true);
        worker.start();
        return new LlmStream(queue, () -> cancelStream(worker, queue), () -> joinWorker(worker));
    }

    /** 取消当前流：中断 worker + 关闭响应体 + 投递「已中断」事件（幂等） */
    private void cancelStream(Thread worker, BlockingQueue<StreamEvent> queue) {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        worker.interrupt();
        java.io.InputStream body = activeBody;
        if (body != null) {
            try {
                body.close();
            } catch (java.io.IOException ignored) {
                // 关闭失败不影响取消语义
            }
        }
        put(queue, new StreamEvent.Error("已中断"));
    }

    /** 带超时等待流线程结束（退出时优雅关闭，M4，spec F5） */
    private void joinWorker(Thread worker) {
        try {
            worker.join(3_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
            activeBody = response.body();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(activeBody, StandardCharsets.UTF_8))) {
                SseParser parser = new SseParser();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancelled.get()) {
                        break;
                    }
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
            } finally {
                activeBody = null;
            }
            if (!ended && !cancelled.get()) {
                // 流 EOF 但未收到协议结束事件：兜底，保证调用方不永久阻塞
                onStreamEof(queue);
            }
        } catch (Exception e) {
            if (!cancelled.get()) {
                put(queue, new StreamEvent.Error("请求失败: " + safeMessage(e)));
            }
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
