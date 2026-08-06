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
 * Anthropic Claude Messages API 客户端。
 *
 * <p>POST {baseUrl}/v1/messages，SSE 流式；支持 extended thinking（spec F7）：
 * thinking_delta → {@link StreamEvent.ThinkingDelta}、signature_delta →
 * {@link StreamEvent.ThinkingComplete}。多轮时把上一轮 assistant 的 thinking 块
 * 与 signature 回传（Anthropic 协议硬性要求，spec F4+F7）。
 */
public class AnthropicClient extends AbstractStreamingClient {

    private static final String API_VERSION = "2023-06-01";
    private static final int THINKING_BUDGET_TOKENS = 32000;
    /** 非思考模式默认最大输出；思考模式下需大于 budget_tokens（Anthropic 协议要求） */
    private static final int MAX_OUTPUT_TOKENS_PLAIN = 8192;
    private static final int MAX_OUTPUT_TOKENS_THINKING = 64000;

    // 每个流的状态（M1 单线程对话，一次只有一个流，字段级状态足够）
    private int inputTokens;
    private int outputTokens;
    private String stopReason;
    private boolean inThinking;
    private final StringBuilder thinkingAccum = new StringBuilder();
    private String thinkingSignature;

    public AnthropicClient(ProviderConfig config) {
        super(config);
    }

    @Override
    protected void onStreamStart() {
        inputTokens = 0;
        outputTokens = 0;
        stopReason = "end_turn";
        inThinking = false;
        thinkingAccum.setLength(0);
        thinkingSignature = "";
    }

    @Override
    protected HttpRequest buildRequest(ChatRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModel());
        body.put("system", request.systemPrompt());
        body.put("messages", buildMessages(request.messages()));
        body.put("max_tokens", config.isThinking() ? MAX_OUTPUT_TOKENS_THINKING : MAX_OUTPUT_TOKENS_PLAIN);
        body.put("stream", true);
        if (config.isThinking()) {
            // LinkedHashMap 保证字段顺序，便于请求体断言与调试
            Map<String, Object> thinking = new LinkedHashMap<>();
            thinking.put("type", "enabled");
            thinking.put("budget_tokens", THINKING_BUDGET_TOKENS);
            body.put("thinking", thinking);
        }
        return HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrl() + "/v1/messages"))
                .header("x-api-key", config.getApiKey())
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(body)))
                .build();
    }

    /** 消息翻译：assistant 带 thinking 时输出 content blocks（thinking + text）实现多轮回传 */
    private List<Map<String, Object>> buildMessages(List<Message> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Message m : messages) {
            Role role = m.getRole();
            if (role == Role.ASSISTANT && m.getThinking() != null && !m.getThinking().isEmpty()) {
                List<Map<String, Object>> blocks = new ArrayList<>();
                blocks.add(ordered("type", "thinking", "thinking", m.getThinking(),
                        "signature", m.getThinkingSignature() == null ? "" : m.getThinkingSignature()));
                blocks.add(ordered("type", "text", "text", m.getContent() == null ? "" : m.getContent()));
                Map<String, Object> assistant = new LinkedHashMap<>();
                assistant.put("role", "assistant");
                assistant.put("content", blocks);
                result.add(assistant);
            } else {
                result.add(ordered("role", role.wire(), "content", m.getContent() == null ? "" : m.getContent()));
            }
        }
        return result;
    }

    /** 构造有序 Map（LinkedHashMap 保证字段顺序稳定，便于调试与断言） */
    private static Map<String, Object> ordered(String... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Override
    protected boolean isStreamEnd(SseEvent sse) {
        return "message_stop".equals(sse.event());
    }

    @Override
    protected void handleEvent(SseEvent sse, BlockingQueue<StreamEvent> queue) {
        try {
            JsonNode data = MAPPER.readTree(sse.data());
            switch (sse.event() == null ? "" : sse.event()) {
                case "message_start" -> inputTokens =
                        data.path("message").path("usage").path("input_tokens").asInt(0);
                case "content_block_start" -> {
                    if ("thinking".equals(data.path("content_block").path("type").asText(""))) {
                        inThinking = true;
                        thinkingAccum.setLength(0);
                        thinkingSignature = "";
                    }
                }
                case "content_block_delta" -> {
                    String deltaType = data.path("delta").path("type").asText("");
                    switch (deltaType) {
                        case "thinking_delta" -> {
                            String text = data.path("delta").path("thinking").asText("");
                            thinkingAccum.append(text);
                            put(queue, new StreamEvent.ThinkingDelta(text));
                        }
                        case "signature_delta" ->
                                thinkingSignature = data.path("delta").path("signature").asText("");
                        case "text_delta" ->
                                put(queue, new StreamEvent.TextDelta(data.path("delta").path("text").asText("")));
                        default -> { /* input_json_delta 等 M2 处理 */ }
                    }
                }
                case "content_block_stop" -> {
                    if (inThinking) {
                        put(queue, new StreamEvent.ThinkingComplete(thinkingSignature));
                        inThinking = false;
                    }
                }
                case "message_delta" -> {
                    String reason = data.path("delta").path("stop_reason").asText("");
                    if (!reason.isEmpty()) {
                        stopReason = reason;
                    }
                    outputTokens = data.path("usage").path("output_tokens").asInt(0);
                }
                case "message_stop" -> put(queue, new StreamEvent.StreamEnd(stopReason, inputTokens, outputTokens));
                default -> { /* ping 等忽略 */ }
            }
        } catch (Exception e) {
            put(queue, new StreamEvent.Error("流事件解析失败: " + e.getMessage()));
        }
    }
}
