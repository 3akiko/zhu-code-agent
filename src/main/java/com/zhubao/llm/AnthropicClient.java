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

    public AnthropicClient(ProviderConfig config) {
        super(config);
    }

    /** per-call 状态：Anthropic 协议（thinking + 单块 tool_use 累积），M5 spec F1 */
    @Override
    protected StreamState newStreamState() {
        AnthropicStreamState s = new AnthropicStreamState();
        s.stopReason = "end_turn";
        return s;
    }

    @Override
    protected HttpRequest buildRequest(ChatRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModel());
        // M4（spec F4）：prompt 缓存断点。开启时 system 用块数组 + cache_control；
        // 关闭时（prompt_cache: false，兼容不识别 cache_control 的端点）回退 M1 字符串 system
        if (config.effectivePromptCache()) {
            body.put("system", List.of(ordered("type", "text", "text", request.systemPrompt(),
                    "cache_control", Map.of("type", "ephemeral"))));
        } else {
            body.put("system", request.systemPrompt());
        }
        body.put("messages", buildMessages(request.messages()));
        // M4（spec F7）：max_tokens 由 provider 配置 / 模型表解析，不再写死
        body.put("max_tokens", config.effectiveMaxTokens(config.isThinking()));
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
                // M4（spec F4）：每个工具定义打 cache_control 断点（prompt 缓存）；关闭时不带
                if (config.effectivePromptCache()) {
                    tools.add(ordered("name", t.name(), "description", t.description(), "input_schema", t.inputSchema(),
                            "cache_control", Map.of("type", "ephemeral")));
                } else {
                    tools.add(ordered("name", t.name(), "description", t.description(), "input_schema", t.inputSchema()));
                }
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
    protected void handleEvent(SseEvent sse, BlockingQueue<StreamEvent> queue, StreamState state) {
        AnthropicStreamState s = (AnthropicStreamState) state;
        try {
            JsonNode data = MAPPER.readTree(sse.data());
            switch (sse.event() == null ? "" : sse.event()) {
                case "message_start" -> s.inputTokens =
                        data.path("message").path("usage").path("input_tokens").asInt(0);
                case "content_block_start" -> {
                    String type = data.path("content_block").path("type").asText("");
                    if ("thinking".equals(type)) {
                        s.inThinking = true;
                        s.thinkingAccum.setLength(0);
                        s.thinkingSignature = "";
                    } else if ("tool_use".equals(type)) {
                        s.inToolUse = true;
                        s.toolUseId = data.path("content_block").path("id").asText("");
                        s.toolUseName = data.path("content_block").path("name").asText("");
                        s.toolUseJsonAccum.setLength(0);
                    }
                }
                case "content_block_delta" -> {
                    String deltaType = data.path("delta").path("type").asText("");
                    switch (deltaType) {
                        case "thinking_delta" -> {
                            String text = data.path("delta").path("thinking").asText("");
                            s.thinkingAccum.append(text);
                            put(queue, new StreamEvent.ThinkingDelta(text));
                        }
                        case "signature_delta" ->
                                s.thinkingSignature = data.path("delta").path("signature").asText("");
                        case "text_delta" ->
                                put(queue, new StreamEvent.TextDelta(data.path("delta").path("text").asText("")));
                        case "input_json_delta" ->
                                s.toolUseJsonAccum.append(data.path("delta").path("partial_json").asText(""));
                        default -> { /* 其他 delta 类型忽略 */ }
                    }
                }
                case "content_block_stop" -> {
                    if (s.inThinking) {
                        put(queue, new StreamEvent.ThinkingComplete(s.thinkingSignature));
                        s.inThinking = false;
                    }
                    if (s.inToolUse) {
                        put(queue, new StreamEvent.ToolCall(s.toolUseId, s.toolUseName, s.toolUseJsonAccum.toString()));
                        s.inToolUse = false;
                    }
                }
                case "message_delta" -> {
                    String reason = data.path("delta").path("stop_reason").asText("");
                    if (!reason.isEmpty()) {
                        s.stopReason = reason;
                    }
                    s.outputTokens = data.path("usage").path("output_tokens").asInt(0);
                    // M4（spec F4）：prompt 缓存命中/创建统计
                    s.cacheReadTokens = data.path("usage").path("cache_read_input_tokens").asInt(0);
                    s.cacheCreationTokens = data.path("usage").path("cache_creation_input_tokens").asInt(0);
                }
                case "message_stop" -> put(queue, new StreamEvent.StreamEnd(
                        s.stopReason, s.inputTokens, s.outputTokens, s.cacheReadTokens, s.cacheCreationTokens));
                default -> { /* ping 等忽略 */ }
            }
        } catch (Exception e) {
            put(queue, new StreamEvent.Error("流事件解析失败: " + e.getMessage()));
        }
    }
}
