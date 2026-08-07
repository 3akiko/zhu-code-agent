package com.zhubao.llm;

import com.zhubao.conversation.Message;

import java.util.List;

/**
 * 统一的 LLM 请求结构：系统提示词 + 消息历史 + 工具定义（M2 新增）。
 * 各协议客户端负责把它翻译成各自的 API 请求格式（Anthropic Messages / OpenAI Chat Completions）。
 */
public record ChatRequest(String systemPrompt, List<Message> messages, List<ToolSpec> tools) {

    /** M1 兼容：不带工具 */
    public ChatRequest(String systemPrompt, List<Message> messages) {
        this(systemPrompt, messages, List.of());
    }
}
