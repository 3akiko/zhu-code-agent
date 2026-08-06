package com.zhubao.session;

import com.zhubao.conversation.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个会话 = 元数据 + 消息历史。
 * 纯数据结构，供 Jackson 序列化到 ~/.zhu-code-agent/sessions/{id}.json。
 */
public class Session {

    private SessionMeta meta;
    private List<Message> messages = new ArrayList<>();

    /** Jackson 反序列化需要无参构造 */
    public Session() {
    }

    public Session(SessionMeta meta, List<Message> messages) {
        this.meta = meta;
        this.messages = messages != null ? messages : new ArrayList<>();
    }

    public SessionMeta getMeta() {
        return meta;
    }

    public void setMeta(SessionMeta meta) {
        this.meta = meta;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages != null ? messages : new ArrayList<>();
    }
}
