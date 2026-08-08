package com.zhubao.session;

import com.zhubao.conversation.Message;
import com.zhubao.conversation.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SessionStoreTest {

    @TempDir
    Path tmp;

    private final ProviderSnapshot provider =
            new ProviderSnapshot("claude", "anthropic", "claude-sonnet-4-5", "https://api.anthropic.com");

    private Session newSession(String id, Instant updatedAt, Message... messages) {
        SessionMeta meta = new SessionMeta(id, Instant.parse("2026-08-07T10:00:00Z"), updatedAt,
                "标题", messages.length, provider);
        return new Session(meta, List.of(messages));
    }

    @Test
    void saveListLoadRoundTrip() throws Exception {
        SessionStore store = new SessionStore(tmp);
        Session s = newSession("s1", Instant.parse("2026-08-07T10:00:00Z"),
                new Message(Role.USER, "你好"), new Message(Role.ASSISTANT, "你好！"));

        store.save(s);

        List<SessionMeta> metas = store.list();
        assertEquals(1, metas.size());
        assertEquals("s1", metas.get(0).id());
        assertEquals("标题", metas.get(0).title());
        assertEquals(2, metas.get(0).messageCount());
        assertEquals(provider, metas.get(0).provider());

        Optional<Session> loaded = store.load("s1");
        assertTrue(loaded.isPresent());
        assertEquals(2, loaded.get().getMessages().size());
        assertEquals("你好", loaded.get().getMessages().get(0).getContent());
        assertEquals(Role.ASSISTANT, loaded.get().getMessages().get(1).getRole());
    }

    @Test
    void thinkingFieldsRoundTrip() throws Exception {
        SessionStore store = new SessionStore(tmp);
        Session s = newSession("t1", Instant.parse("2026-08-07T10:00:00Z"),
                new Message(Role.USER, "q"),
                new Message(Role.ASSISTANT, "answer", "thinking text", "sig-xyz"));
        store.save(s);

        Session loaded = store.load("t1").orElseThrow();
        Message assistant = loaded.getMessages().get(1);
        assertEquals("thinking text", assistant.getThinking());
        assertEquals("sig-xyz", assistant.getThinkingSignature());
    }

    @Test
    void listOrderedByUpdatedAtDescending() throws Exception {
        SessionStore store = new SessionStore(tmp);
        store.save(newSession("older", Instant.parse("2026-08-07T08:00:00Z")));
        store.save(newSession("newer", Instant.parse("2026-08-07T12:00:00Z")));
        store.save(newSession("middle", Instant.parse("2026-08-07T10:00:00Z")));

        List<SessionMeta> metas = store.list();
        assertEquals(List.of("newer", "middle", "older"), metas.stream().map(SessionMeta::id).toList());
    }

    @Test
    void corruptFileSkippedWithoutCrash() throws Exception {
        SessionStore store = new SessionStore(tmp);
        store.save(newSession("good", Instant.parse("2026-08-07T10:00:00Z")));
        Files.writeString(tmp.resolve("bad.json"), "not json at all {{{");

        List<SessionMeta> metas = store.list();
        assertEquals(1, metas.size());
        assertEquals("good", metas.get(0).id());
        assertEquals(Optional.empty(), store.load("bad"));
        // good 文件不受影响
        assertTrue(store.load("good").isPresent());
    }

    @Test
    void loadMissingReturnsEmpty() {
        SessionStore store = new SessionStore(tmp);
        assertEquals(Optional.empty(), store.load("nope"));
    }

    @Test
    void noTmpFilesLeftAfterSave() throws Exception {
        SessionStore store = new SessionStore(tmp);
        store.save(newSession("a", Instant.parse("2026-08-07T10:00:00Z")));

        try (Stream<Path> files = Files.list(tmp)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "原子写不应残留 .tmp 文件");
        }
    }

    @Test
    void sessionJsonNeverContainsApiKey() throws Exception {
        SessionStore store = new SessionStore(tmp);
        store.save(newSession("sec", Instant.parse("2026-08-07T10:00:00Z")));

        String json = Files.readString(tmp.resolve("sec.json"));
        assertFalse(json.contains("apiKey"));
        assertFalse(json.contains("api_key"));
        // provider 快照只含 name/protocol/model/baseUrl
        assertTrue(json.contains("\"protocol\":\"anthropic\""));
    }

    @Test
    void directoryCreatedAutomatically() {
        Path dir = tmp.resolve("nested/sessions");
        SessionStore store = new SessionStore(dir);
        store.save(newSession("x", Instant.parse("2026-08-07T10:00:00Z")));
        assertTrue(Files.exists(dir.resolve("x.json")));
    }

    @Test
    void sessionIdUniqueFormat() {
        String id = SessionMeta.newId();
        assertTrue(id.matches("\\d{8}-\\d{6}-[0-9a-f]{1,4}"), "id 格式不符: " + id);
    }

    // ── M2：工具消息落盘 / 64KB 截断 / 旧格式迁移 ─────────────────────────

    @Test
    void toolMessagesRoundTrip() throws Exception {
        SessionStore store = new SessionStore(tmp);
        Session s = newSession("t1", Instant.parse("2026-08-07T10:00:00Z"),
                new Message(Role.USER, "读文件"),
                new Message(Role.ASSISTANT, java.util.List.of(
                        new com.zhubao.conversation.ContentBlock.ToolUseBlock("tu1", "read_file", "{\"path\":\"a.txt\"}")),
                        null, null),
                new Message(Role.USER, java.util.List.of(
                        new com.zhubao.conversation.ContentBlock.ToolResultBlock("tu1", "read_file", false, "内容")),
                        null, null));
        store.save(s);

        Session loaded = store.load("t1").orElseThrow();
        Message assistant = loaded.getMessages().get(1);
        Message result = loaded.getMessages().get(2);
        assertEquals(1, assistant.getBlocks().size());
        com.zhubao.conversation.ContentBlock.ToolUseBlock tu =
                (com.zhubao.conversation.ContentBlock.ToolUseBlock) assistant.getBlocks().get(0);
        assertEquals("tu1", tu.id());
        assertEquals("{\"path\":\"a.txt\"}", tu.argumentsJson(), "tool_use 参数必须完整往返（回归 P1）");
        assertEquals("内容", ((com.zhubao.conversation.ContentBlock.ToolResultBlock) result.getBlocks().get(0)).output());
    }

    @Test
    void toolResultTruncatedAt64KOnSave() throws Exception {
        SessionStore store = new SessionStore(tmp);
        String big = "x".repeat(70_000);
        Session s = newSession("t2", Instant.parse("2026-08-07T10:00:00Z"),
                new Message(Role.USER, java.util.List.of(
                        new com.zhubao.conversation.ContentBlock.ToolResultBlock("tu1", "bash", false, big)), null, null));
        store.save(s);

        Session loaded = store.load("t2").orElseThrow();
        String output = ((com.zhubao.conversation.ContentBlock.ToolResultBlock) loaded.getMessages().get(0).getBlocks().get(0)).output();
        assertTrue(output.length() <= SessionStore.TOOL_RESULT_PERSIST_CAP + 40, "落盘结果应被截断，实际长度 " + output.length());
        assertTrue(output.contains("已截断"));
        String original = ((com.zhubao.conversation.ContentBlock.ToolResultBlock) s.getMessages().get(0).getBlocks().get(0)).output();
        assertEquals(70_000, original.length());
    }

    @Test
    void legacyStringContentMigratedToTextBlock() throws Exception {
        SessionStore store = new SessionStore(tmp);
        String oldJson = """
                {"meta":{"id":"old1","createdAt":"2026-08-07T10:00:00Z","updatedAt":"2026-08-07T10:00:00Z","title":"旧会话","messageCount":1,"provider":{"name":"claude","protocol":"anthropic","model":"claude-sonnet-4-5","baseUrl":"https://api.anthropic.com"}},"messages":[{"role":"user","content":"旧问题"}]}
                """;
        Files.writeString(tmp.resolve("old1.json"), oldJson);

        Session loaded = store.load("old1").orElseThrow();
        assertEquals(1, loaded.getMessages().size());
        assertEquals("旧问题", loaded.getMessages().get(0).getContent());
        assertEquals(1, loaded.getMessages().get(0).getBlocks().size());
        assertInstanceOf(com.zhubao.conversation.ContentBlock.TextBlock.class,
                loaded.getMessages().get(0).getBlocks().get(0));
    }
}