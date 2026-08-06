package com.zhubao.conversation;

import com.zhubao.llm.ChatRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * 内存态多轮对话历史（spec F4）。
 *
 * 职责：追加用户/助手消息、构造发送给 LLM 的统一请求、生成会话标题摘要与消息数。
 * 本类不依赖 session 包，避免会话模块与对话模块互相依赖；会话落盘由上层（tui）组装。
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

    /** 完整历史（不可变视图，防止外部修改） */
    public List<Message> getMessages() {
        return List.copyOf(history);
    }

    /** 构造统一请求：系统提示词 + 完整历史 */
    public ChatRequest buildRequest(String systemPrompt) {
        return new ChatRequest(systemPrompt, getMessages());
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
