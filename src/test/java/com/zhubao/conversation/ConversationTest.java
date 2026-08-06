package com.zhubao.conversation;

import com.zhubao.llm.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationTest {

    @Test
    void historyAppendsInOrder() {
        Conversation c = new Conversation();
        c.addUser("你好");
        c.addAssistant("你好！", null, null);
        c.addUser("再问一个");
        assertEquals(3, c.messageCount());
        List<Message> msgs = c.getMessages();
        assertEquals(Role.USER, msgs.get(0).getRole());
        assertEquals("你好", msgs.get(0).getContent());
        assertEquals(Role.ASSISTANT, msgs.get(1).getRole());
        assertEquals(Role.USER, msgs.get(2).getRole());
    }

    @Test
    void buildRequestCarriesFullHistory() {
        Conversation c = new Conversation();
        c.addUser("q1");
        c.addAssistant("a1", "thinking-text", "sig-1");
        c.addUser("q2");

        ChatRequest req = c.buildRequest("SYSTEM");
        assertEquals("SYSTEM", req.systemPrompt());
        assertEquals(3, req.messages().size());
        // assistant 消息保留 thinking 与 signature 供 Anthropic 多轮回传
        Message assistant = req.messages().get(1);
        assertEquals("thinking-text", assistant.getThinking());
        assertEquals("sig-1", assistant.getThinkingSignature());
    }

    @Test
    void restoreFromInitialMessages() {
        Conversation c = new Conversation(List.of(new Message(Role.USER, "旧问题"), new Message(Role.ASSISTANT, "旧回答")));
        assertEquals(2, c.messageCount());
        c.addUser("新问题");
        assertEquals(3, c.messageCount());
    }

    @Test
    void previewTitleFromFirstUserMessage() {
        Conversation c = new Conversation();
        assertEquals("新对话", c.previewTitle());
        c.addUser("帮我解释一下 JVM 内存模型");
        assertEquals("帮我解释一下 JVM 内存模型", c.previewTitle());
    }

    @Test
    void previewTitleTruncatedAt30() {
        Conversation c = new Conversation();
        c.addUser("这是一条用于测试标题截断的超长用户消息，长度肯定远远超过三十个字符的限制了，对吧？");
        String title = c.previewTitle();
        assertTrue(title.endsWith("…"));
        assertEquals(31, title.length()); // 30 字符 + 省略号
    }

    @Test
    void previewTitleNormalizesWhitespace() {
        Conversation c = new Conversation();
        c.addUser("第一行\n第二行   缩进");
        assertEquals("第一行 第二行 缩进", c.previewTitle());
    }

    @Test
    void getMessagesIsUnmodifiable() {
        Conversation c = new Conversation();
        c.addUser("x");
        List<Message> msgs = c.getMessages();
        assertThrows(UnsupportedOperationException.class, () -> msgs.clear());
    }
}
