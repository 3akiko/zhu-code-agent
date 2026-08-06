package com.zhubao.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试用本地 mock HTTP 服务器：捕获每个请求体，交由 handler 写回响应。
 * 用 JDK 内置 com.sun.net.httpserver，无需真实 API 密钥即可测流式链路。
 */
public class MockHttpServer implements AutoCloseable {

    /** 与 BiConsumer 类似，但允许抛受检异常（writeSse/writeStatus 会抛 IOException） */
    @FunctionalInterface
    public interface ThrowingHandler {
        void accept(HttpExchange exchange, String requestBody) throws IOException;
    }

    private final HttpServer server;
    private final List<String> capturedBodies = new CopyOnWriteArrayList<>();

    public List<String> capturedBodies() {
        return capturedBodies;
    }

    public MockHttpServer(ThrowingHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            capturedBodies.add(new String(body, StandardCharsets.UTF_8));
            handler.accept(exchange, capturedBodies.get(capturedBodies.size() - 1));
        });
        server.start();
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public String lastRequestBody() {
        return capturedBodies.get(capturedBodies.size() - 1);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public static void writeSse(HttpExchange exchange, String sse) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0); // 0 = chunked
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(sse.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static void writeStatus(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
