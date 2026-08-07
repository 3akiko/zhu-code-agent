package com.zhubao.conversation;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 消息内容块（M2 spec F9）：把一条消息的结构化内容统一为块列表。
 * 协议无关；Anthropic / OpenAI 客户端负责翻译为各自 wire 格式。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ContentBlock.TextBlock.class, name = "text"),
        @JsonSubTypes.Type(value = ContentBlock.ToolUseBlock.class, name = "tool_use"),
        @JsonSubTypes.Type(value = ContentBlock.ToolResultBlock.class, name = "tool_result")
})
public sealed interface ContentBlock {

    /** 普通文本块 */
    record TextBlock(String text) implements ContentBlock {
    }

    /** 工具调用声明（assistant 消息内） */
    record ToolUseBlock(String id, String name, String argumentsJson) implements ContentBlock {
    }

    /** 工具执行结果（user 消息内；id 对应 ToolUseBlock.id） */
    record ToolResultBlock(String id, String name, boolean isError, String output) implements ContentBlock {
    }
}
