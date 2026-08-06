package com.zhubao.conversation;

/**
 * 一条对话消息。
 *
 * <p>system 提示词由各协议客户端单独处理，不放在这里。
 *
 * <p>thinking / thinkingSignature 仅 assistant 消息可能携带：
 * Anthropic 协议要求 extended thinking 之后的多轮请求必须回传上一轮 assistant 的
 * thinking 内容块与 signature，否则报错。M1 因此保留这两个字段用于多轮回传
 * （spec F4 + F7 组合场景）。JSON 序列化时 null 字段省略（session 落盘用）。
 */
public class Message {

    private Role role;
    private String content;
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
        this.content = content;
        this.thinking = thinking;
        this.thinkingSignature = thinkingSignature;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
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
