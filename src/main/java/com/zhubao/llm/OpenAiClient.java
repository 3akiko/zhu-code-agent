package com.zhubao.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhubao.config.ProviderConfig;
import com.zhubao.conversation.Message;
import com.zhubao.conversation.Role;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 * OpenAI Chat Completions API 客户端。
 *
 * <p>POST {baseUrl}/v1/chat/completions，SSE 流式（data: 块 + [DONE]）。
 * system 提示词作为 role=system 的首条消息；请求 stream_options 以在最后一块
 * 拿到 usage。OpenAI 无 thinking 事件。
 */
public class OpenAiClient extends AbstractStreamingClient {

    private int inputTokens;
    private int outputTokens;
    private String stopReason;

    public OpenAiClient(ProviderConfig config) {
        super(config);
    }

    @Override
    protected void onStreamStart() {
        inputTokens = 0;
        outputTokens = 0;
        stopReason = "stop";
    }

    @Override
    protected HttpRequest buildRequest(ChatRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(msg("system", request.systemPrompt()));
        }
        for (Message m : request.messages()) {
            messages.add(msg(m.getRole().wire(), m.getContent() == null ? "" : m.getContent()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModel());
        body.put("messages", messages);
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));
        return HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrl() + "/v1/chat/completions"))
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(body)))
                .build();
    }

    /** 构造一条消息（LinkedHashMap 保证字段顺序稳定，便于调试与断言） */
    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    @Override
    protected boolean isStreamEnd(SseEvent sse) {
        return "[DONE]".equals(sse.data());
    }

    @Override
    protected void handleEvent(SseEvent sse, BlockingQueue<StreamEvent> queue) {
        if ("[DONE]".equals(sse.data())) {
            // 结束标志不是 JSON，直接收尾（含 usage）
            put(queue, new StreamEvent.StreamEnd(stopReason, inputTokens, outputTokens));
            return;
        }
        try {
            JsonNode data = MAPPER.readTree(sse.data());
            JsonNode choices = data.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode choice = choices.get(0);
                String content = choice.path("delta").path("content").asText("");
                if (!content.isEmpty()) {
                    put(queue, new StreamEvent.TextDelta(content));
                }
                String finish = choice.path("finish_reason").asText("");
                if (!finish.isEmpty()) {
                    stopReason = finish;
                }
            }
            if (data.has("usage")) {
                inputTokens = data.path("usage").path("prompt_tokens").asInt(0);
                outputTokens = data.path("usage").path("completion_tokens").asInt(0);
            }
        } catch (Exception e) {
            put(queue, new StreamEvent.Error("流事件解析失败: " + e.getMessage()));
        }
    }
}
