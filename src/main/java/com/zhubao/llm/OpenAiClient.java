package com.zhubao.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhubao.config.ProviderConfig;
import com.zhubao.conversation.ContentBlock;
import com.zhubao.conversation.Message;
import com.zhubao.conversation.Role;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 * OpenAI Chat Completions API 客户端。
 *
 * <p>POST {baseUrl}/v1/chat/completions，SSE 流式（data: 块 + [DONE]）。
 * system 提示词作为 role=system 的首条消息；请求 stream_options 以在最后一块
 * 拿到 usage。M2 F8：请求体携带 tools（function calling），delta.tool_calls 按
 * index 累积 → 流结束时发 {@link StreamEvent.ToolCall}；tool_result 以 role=tool
 * 消息回传。
 */
public class OpenAiClient extends AbstractStreamingClient {

    public OpenAiClient(ProviderConfig config) {
        super(config);
    }

    /** per-call 状态：OpenAI 协议（多 index tool_calls 累积），M5 spec F1 */
    @Override
    protected StreamState newStreamState() {
        OpenAiStreamState s = new OpenAiStreamState();
        s.stopReason = "stop";
        return s;
    }

    @Override
    protected HttpRequest buildRequest(ChatRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(msg("system", request.systemPrompt()));
        }
        for (Message m : request.messages()) {
            messages.addAll(translate(m));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModel());
        body.put("messages", messages);
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));
        // M4（spec F7）：OpenAI 仅显式配置 max_tokens 时发送（默认行为不变，避免回归）
        if (config.getMaxTokens() != null) {
            body.put("max_tokens", config.getMaxTokens());
        }
        if (request.tools() != null && !request.tools().isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (ToolSpec t : request.tools()) {
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", t.name());
                fn.put("description", t.description());
                fn.put("parameters", t.inputSchema());
                tools.add(ordered("type", "function", "function", fn));
            }
            body.put("tools", tools);
        }
        return HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrl() + "/v1/chat/completions"))
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(body)))
                .build();
    }

    /** 消息翻译：assistant 带 tool_calls、user 的 tool_result 拆成 role=tool 消息 */
    private List<Map<String, Object>> translate(Message m) {
        List<Map<String, Object>> out = new ArrayList<>();
        Role role = m.getRole();
        boolean hasToolUse = m.getBlocks().stream().anyMatch(ContentBlock.ToolUseBlock.class::isInstance);
        boolean hasToolResult = m.getBlocks().stream().anyMatch(ContentBlock.ToolResultBlock.class::isInstance);
        if (role == Role.ASSISTANT && hasToolUse) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("role", "assistant");
            StringBuilder text = new StringBuilder();
            List<Map<String, Object>> calls = new ArrayList<>();
            for (ContentBlock b : m.getBlocks()) {
                if (b instanceof ContentBlock.TextBlock t) {
                    text.append(t.text());
                } else if (b instanceof ContentBlock.ToolUseBlock tu) {
                    Map<String, Object> fn = new LinkedHashMap<>();
                    fn.put("name", tu.name());
                    fn.put("arguments", tu.argumentsJson());
                    calls.add(ordered("id", tu.id(), "type", "function", "function", fn));
                }
            }
            am.put("content", text.toString());
            if (!calls.isEmpty()) {
                am.put("tool_calls", calls);
            }
            out.add(am);
        } else if (role == Role.USER && hasToolResult) {
            StringBuilder text = new StringBuilder();
            for (ContentBlock b : m.getBlocks()) {
                if (b instanceof ContentBlock.TextBlock t) {
                    text.append(t.text());
                } else if (b instanceof ContentBlock.ToolResultBlock tr) {
                    out.add(ordered("role", "tool", "tool_call_id", tr.id(), "content", tr.output()));
                }
            }
            if (!text.isEmpty()) {
                out.add(msg("user", text.toString()));
            }
        } else {
            out.add(msg(role.wire(), m.getContent() == null ? "" : m.getContent()));
        }
        return out;
    }

    /** 构造一条消息（LinkedHashMap 保证字段顺序稳定，便于调试与断言） */
    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Override
    protected boolean isStreamEnd(SseEvent sse) {
        return "[DONE]".equals(sse.data());
    }

    @Override
    protected void handleEvent(SseEvent sse, BlockingQueue<StreamEvent> queue, StreamState state) {
        OpenAiStreamState s = (OpenAiStreamState) state;
        if ("[DONE]".equals(sse.data())) {
            // 结束标志：先发累积的工具调用，再发 StreamEnd（含 usage）
            emitToolCalls(queue, s);
            put(queue, new StreamEvent.StreamEnd(s.stopReason, s.inputTokens, s.outputTokens, s.cacheReadTokens, 0));
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
                    s.stopReason = finish;
                }
                JsonNode toolCalls = choice.path("delta").path("tool_calls");
                if (toolCalls.isArray()) {
                    for (JsonNode tc : toolCalls) {
                        int index = tc.path("index").asInt(0);
                        OpenAiStreamState.ToolAccum acc =
                                s.toolAccums.computeIfAbsent(index, k -> new OpenAiStreamState.ToolAccum());
                        if (tc.hasNonNull("id")) {
                            acc.id = tc.path("id").asText();
                        }
                        JsonNode fn = tc.path("function");
                        if (fn.hasNonNull("name")) {
                            acc.name = fn.path("name").asText();
                        }
                        if (fn.hasNonNull("arguments")) {
                            acc.args.append(fn.path("arguments").asText());
                        }
                    }
                }
            }
            if (data.has("usage")) {
                s.inputTokens = data.path("usage").path("prompt_tokens").asInt(0);
                s.outputTokens = data.path("usage").path("completion_tokens").asInt(0);
                // M4（spec F4）：OpenAI prompt_tokens_details.cached_tokens / DeepSeek prompt_cache_hit_tokens
                s.cacheReadTokens = data.path("usage").path("prompt_tokens_details").path("cached_tokens").asInt(0);
                if (s.cacheReadTokens == 0) {
                    s.cacheReadTokens = data.path("usage").path("prompt_cache_hit_tokens").asInt(0);
                }
            }
        } catch (Exception e) {
            put(queue, new StreamEvent.Error("流事件解析失败: " + e.getMessage()));
        }
    }

    /** 按 index 升序发出全部累积的工具调用（状态在 per-call state 内） */
    private void emitToolCalls(BlockingQueue<StreamEvent> queue, OpenAiStreamState s) {
        List<Integer> indexes = new ArrayList<>(s.toolAccums.keySet());
        indexes.sort(Comparator.naturalOrder());
        for (int idx : indexes) {
            OpenAiStreamState.ToolAccum acc = s.toolAccums.get(idx);
            put(queue, new StreamEvent.ToolCall(acc.id, acc.name, acc.args.toString()));
        }
        s.toolAccums.clear();
    }
}
