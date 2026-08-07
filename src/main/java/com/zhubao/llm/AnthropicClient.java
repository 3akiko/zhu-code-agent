package com.zhubao.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhubao.config.ProviderConfig;
import com.zhubao.conversation.ContentBlock;
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
 * <p>POST {baseUrl}/v1/messages，SSE 流式；支持 extended thinking（spec F7）与
 * 工具调用（M2 F8）：content_block_start(tool_use) + input_json_delta 累积 →
 * content_block_stop 时发 {@link StreamEvent.ToolCall}。请求体携带 tools 定义，
 * tool_result 以 user 消息内嵌 tool_result 块回传。
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
    // 工具调用累积（M2）
    private boolean inToolUse;
    private String toolUseId;
    private String toolUseName;
    private final StringBuilder toolUseJsonAccum = new StringBuilder();

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
        inToolUse = false;
        toolUseId = "";
        toolUseName = "";
        toolUseJsonAccum.setLength(0);
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
        if (request.tools() != null && !request.tools().isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (ToolSpec t : request.tools()) {
                tools.add(ordered("name", t.name(), "description", t.description(), "input_schema", t.inputSchema()));
            }
            body.put("tools", tools);
        }
        return HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrl() + "/v1/messages"))
                .header("x-api-key", config.getApiKey())
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(body)))
                .build();
    }

    /** 消息翻译：assistant 带 thinking/tool_use、user 带 tool_result 时输出内容块数组 */
    private List<Map<String, Object>> buildMessages(List<Message> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Message m : messages) {
            Role role = m.getRole();
            boolean hasThinking = role == Role.ASSISTANT
                    && m.getThinking() != null && !m.getThinking().isEmpty();
            boolean hasToolUse = m.getBlocks().stream().anyMatch(ContentBlock.ToolUseBlock.class::isInstance);
            boolean hasToolResult = m.getBlocks().stream().anyMatch(ContentBlock.ToolResultBlock.class::isInstance);
            if (hasThinking || hasToolUse || hasToolResult) {
                List<Map<String, Object>> blocks = new ArrayList<>();
                if (hasThinking) {
                    blocks.add(ordered("type", "thinking", "thinking", m.getThinking(),
                            "signature", m.getThinkingSignature() == null ? "" : m.getThinkingSignature()));
                }
                for (ContentBlock b : m.getBlocks()) {
                    if (b instanceof ContentBlock.TextBlock t) {
                        blocks.add(ordered("type", "text", "text", t.text()));
                    } else if (b instanceof ContentBlock.ToolUseBlock tu) {
                        blocks.add(ordered("type", "tool_use", "id", tu.id(), "name", tu.name(),
                                "input", parseJson(tu.argumentsJson())));
                    } else if (b instanceof ContentBlock.ToolResultBlock tr) {
                        blocks.add(ordered("type", "tool_result", "tool_use_id", tr.id(),
                                "is_error", tr.isError(), "content", tr.output()));
                    }
                }
                result.add(ordered("role", role.wire(), "content", blocks));
            } else {
                result.add(ordered("role", role.wire(), "content", m.getContent() == null ? "" : m.getContent()));
            }
        }
        return result;
    }

    /** 解析工具参数 JSON；失败回退为空对象（避免请求体带非法 JSON） */
    private static JsonNode parseJson(String json) {
        try {
            JsonNode node = MAPPER.readTree(json == null ? "" : json);
            return node == null ? MAPPER.createObjectNode() : node;
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    /** 构造有序 Map（LinkedHashMap 保证字段顺序稳定，便于调试与断言） */
    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
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
                    String type = data.path("content_block").path("type").asText("");
                    if ("thinking".equals(type)) {
                        inThinking = true;
                        thinkingAccum.setLength(0);
                        thinkingSignature = "";
                    } else if ("tool_use".equals(type)) {
                        inToolUse = true;
                        toolUseId = data.path("content_block").path("id").asText("");
                        toolUseName = data.path("content_block").path("name").asText("");
                        toolUseJsonAccum.setLength(0);
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
                        case "input_json_delta" ->
                                toolUseJsonAccum.append(data.path("delta").path("partial_json").asText(""));
                        default -> { /* 其他 delta 类型忽略 */ }
                    }
                }
                case "content_block_stop" -> {
                    if (inThinking) {
                        put(queue, new StreamEvent.ThinkingComplete(thinkingSignature));
                        inThinking = false;
                    }
                    if (inToolUse) {
                        put(queue, new StreamEvent.ToolCall(toolUseId, toolUseName, toolUseJsonAccum.toString()));
                        inToolUse = false;
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
