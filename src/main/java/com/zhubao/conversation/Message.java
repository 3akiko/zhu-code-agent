package com.zhubao.conversation;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条对话消息（M2 起内容块化，spec F9）。
 *
 * <p>system 提示词由各协议客户端单独处理，不放在这里。
 *
 * <p>thinking / thinkingSignature 仅 assistant 消息可能携带：
 * Anthropic 协议要求 extended thinking 之后的多轮请求必须回传上一轮 assistant 的
 * thinking 内容块与 signature，否则报错。
 *
 * <p>JSON 兼容性：旧 M1 会话文件里 content 是字符串，加载时自动迁移为 TextBlock；
 * 新格式 content 为块数组（含 type 判别字段）。JSON 中 null 字段省略（session 落盘用）。
 */
public class Message {

    private Role role;
    private List<ContentBlock> content = new ArrayList<>();
    private String thinking;
    private String thinkingSignature;

    /** Jackson 反序列化需要无参构造 */
    public Message() {
    }

    public Message(Role role, String content) {
        this(role, content, null, null);
    }

    public Message(Role role, String content, String thinking, String thinkingSignature) {
        this.role = role;
        this.content = new ArrayList<>();
        if (content != null && !content.isEmpty()) {
            this.content.add(new ContentBlock.TextBlock(content));
        }
        this.thinking = thinking;
        this.thinkingSignature = thinkingSignature;
    }

    /** 内容块构造（工具消息用） */
    public Message(Role role, List<ContentBlock> blocks, String thinking, String thinkingSignature) {
        this.role = role;
        this.content = blocks != null ? new ArrayList<>(blocks) : new ArrayList<>();
        this.thinking = thinking;
        this.thinkingSignature = thinkingSignature;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    /** 纯文本视图：拼接所有 TextBlock（供标题/预览/旧代码使用；不是 JSON 属性） */
    @JsonIgnore
    public String getContent() {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : content) {
            if (b instanceof ContentBlock.TextBlock t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    @JsonSetter("content")
    public void setContentNode(JsonNode node) {
        this.content = new ArrayList<>();
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            this.content.add(new ContentBlock.TextBlock(node.asText()));
            return;
        }
        if (node.isArray()) {
            for (JsonNode b : node) {
                this.content.add(readBlock(b));
            }
        }
    }

    private static ContentBlock readBlock(JsonNode b) {
        String type = b.path("type").asText("");
        return switch (type) {
            case "tool_use" -> new ContentBlock.ToolUseBlock(
                    b.path("id").asText(""), b.path("name").asText(""), b.path("arguments").toString());
            case "tool_result" -> new ContentBlock.ToolResultBlock(
                    b.path("id").asText(""), b.path("name").asText(""),
                    b.path("is_error").asBoolean(false), b.path("output").asText(""));
            default -> new ContentBlock.TextBlock(b.path("text").asText(""));
        };
    }

    /** 内容块（JSON 属性 content，序列化为块数组） */
    @JsonGetter("content")
    public List<ContentBlock> getBlocks() {
        return List.copyOf(content);
    }

    public String getThinking() {
        return thinking;
    }

    public void setThinking(String thinking) {
        this.thinking = thinking;
    }

    public String getThinkingSignature() {
        return thinkingSignature;
    }

    public void setThinkingSignature(String thinkingSignature) {
        this.thinkingSignature = thinkingSignature;
    }
}
