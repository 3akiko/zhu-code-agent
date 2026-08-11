package com.zhubao.conversation;

import com.zhubao.llm.ChatRequest;
import com.zhubao.llm.ToolSpec;
import com.zhubao.tool.ToolCall;
import com.zhubao.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 内存态多轮对话历史（spec F4 / M2 F9）。
 *
 * 职责：追加用户/助手消息（含工具调用与结果块）、构造发送给 LLM 的统一请求、
 * 生成会话标题摘要与消息数。本类不依赖 session 包；会话落盘由上层组装。
 */
public class Conversation {

    private static final int TITLE_MAX_LEN = 30;

    private final List<Message> history = new ArrayList<>();

    /** 新建会话 */
    public Conversation() {
    }

    /** 从已有消息恢复会话（spec F9 恢复路径） */
    public Conversation(List<Message> initialMessages) {
        if (initialMessages != null) {
            history.addAll(initialMessages);
        }
    }

    public void addUser(String text) {
        history.add(new Message(Role.USER, text));
    }

    public void addAssistant(String content, String thinking, String thinkingSignature) {
        history.add(new Message(Role.ASSISTANT, content, thinking, thinkingSignature));
    }

    /**
     * 追加一条带工具调用声明的 assistant 消息（spec F1 一次性回填）：
     * 文本块（若有）+ 每个工具调用一个 ToolUseBlock。
     */
    public void addAssistantWithTools(String text, String thinking, String thinkingSignature, List<ToolCall> calls) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            blocks.add(new ContentBlock.TextBlock(text));
        }
        if (calls != null) {
            for (ToolCall c : calls) {
                blocks.add(new ContentBlock.ToolUseBlock(c.id(), c.name(), c.argumentsJson()));
            }
        }
        history.add(new Message(Role.ASSISTANT, blocks, thinking, thinkingSignature));
    }

    /** 追加一条携带全部工具结果的 user 消息（spec F1 一次性回填） */
    public void addToolResultBlocks(List<ToolResult> results) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (results != null) {
            for (ToolResult r : results) {
                blocks.add(new ContentBlock.ToolResultBlock(r.id(), r.name(), r.isError(), r.output()));
            }
        }
        history.add(new Message(Role.USER, blocks, null, null));
    }

    /** 完整历史（不可变视图，防止外部修改） */
    public List<Message> getMessages() {
        return List.copyOf(history);
    }

    /** 整体替换历史（M4 压缩用：snip/截断/摘要折叠后回写；外部负责构造合法消息序） */
    public void replaceMessages(List<Message> messages) {
        history.clear();
        if (messages != null) {
            history.addAll(messages);
        }
    }

    /** 构造统一请求（M1 兼容：不带 tools） */
    public ChatRequest buildRequest(String systemPrompt) {
        return new ChatRequest(systemPrompt, getMessages());
    }

    /** 构造统一请求：系统提示词 + 完整历史 + 工具定义（spec F8） */
    public ChatRequest buildRequest(String systemPrompt, List<ToolSpec> tools) {
        return new ChatRequest(systemPrompt, getMessages(), tools);
    }

    /** 会话标题：首条用户消息前 30 字符；无消息则为「新对话」 */
    public String previewTitle() {
        for (Message m : history) {
            if (m.getRole() == Role.USER && m.getContent() != null && !m.getContent().isBlank()) {
                String oneLine = m.getContent().replaceAll("\\s+", " ").trim();
                if (oneLine.length() <= TITLE_MAX_LEN) {
                    return oneLine;
                }
                return oneLine.substring(0, TITLE_MAX_LEN) + "…";
            }
        }
        return "新对话";
    }

    public int messageCount() {
        return history.size();
    }
}
