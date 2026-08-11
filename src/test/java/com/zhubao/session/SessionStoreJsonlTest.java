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

import static org.junit.jupiter.api.Assertions.*;

/**
 * JSONL 会话存储（M4，spec F6）：追加写 / 恢复 / 旧 .json 迁移 / 坏行 / 截断。
 */
class SessionStoreJsonlTest {

    @TempDir
    Path tmp;

    private final ProviderSnapshot provider =
            new ProviderSnapshot("deepseek", "openai", "deepseek-v4-flash", "https://api.deepseek.com");

    private Session newSession(String id, Instant updatedAt, long in, long out, Message... messages) {
        SessionMeta meta = new SessionMeta(id, Instant.parse("2026-08-07T10:00:00Z"), updatedAt,
                "标题", messages.length, provider, in, out);
        return new Session(meta, List.of(messages));
    }

    @Test
    void savedAsJsonlWithMetaAndMessageLines() throws Exception {
        SessionStore store = new SessionStore(tmp);
        store.save(newSession("a1", Instant.parse("2026-08-07T10:00:00Z"), 10, 20,
                new Message(Role.USER, "你好"), new Message(Role.ASSISTANT, "你好！")));

        Path file = tmp.resolve("a1.jsonl");
        assertTrue(Files.exists(file));
        List<String> lines = Files.readAllLines(file);
        assertEquals(3, lines.size(), "meta 行 + 2 消息行");
        assertTrue(lines.get(0).contains("\"totalInputTokens\":10"));
        assertTrue(lines.get(1).contains("\"role\":\"user\""));
    }

    @Test
    void appendDoesNotDuplicateOldMessages() throws Exception {
        SessionStore store = new SessionStore(tmp);
        Instant t = Instant.parse("2026-08-07T10:00:00Z");
        store.save(newSession("a2", t, 0, 0, new Message(Role.USER, "q1"), new Message(Role.ASSISTANT, "a1")));

        // 第二次保存：新增一条消息 + 刷新 meta
        Session grown = newSession("a2", Instant.parse("2026-08-07T11:00:00Z"), 5, 6,
                new Message(Role.USER, "q1"), new Message(Role.ASSISTANT, "a1"), new Message(Role.USER, "q2"));
        store.save(grown);

        List<String> lines = Files.readAllLines(tmp.resolve("a2.jsonl"));
        assertEquals(5, lines.size(), "meta(2) + q1 + a1 + q2，旧消息不重复");
        Session loaded = store.load("a2").orElseThrow();
        assertEquals(3, loaded.getMessages().size());
        assertEquals("q1", loaded.getMessages().get(0).getContent());
        assertEquals("q2", loaded.getMessages().get(2).getContent());
        assertEquals(5, loaded.getMeta().totalInputTokens());
        assertEquals(6, loaded.getMeta().totalOutputTokens());
        assertEquals(Instant.parse("2026-08-07T11:00:00Z"), loaded.getMeta().updatedAt());
    }

    @Test
    void legacyJsonLoadedThenMigratedToJsonlAndDeleted() throws Exception {
        SessionStore store = new SessionStore(tmp);
        String oldJson = """
                {"meta":{"id":"old2","createdAt":"2026-08-07T10:00:00Z","updatedAt":"2026-08-07T10:00:00Z","title":"旧会话","messageCount":1,"provider":{"name":"deepseek","protocol":"openai","model":"deepseek-v4-flash","baseUrl":"https://api.deepseek.com"}},"messages":[{"role":"user","content":"旧问题"}]}
                """;
        Files.writeString(tmp.resolve("old2.json"), oldJson);

        Session loaded = store.load("old2").orElseThrow();
        assertEquals("旧问题", loaded.getMessages().get(0).getContent());

        // 继续对话后保存 → 转 .jsonl 且旧 .json 删除
        Session grown = new Session(loaded.getMeta(), List.of(
                new Message(Role.USER, "旧问题"), new Message(Role.ASSISTANT, "新答复")));
        store.save(grown);

        assertFalse(Files.exists(tmp.resolve("old2.json")), "迁移后旧 .json 应删除");
        assertTrue(Files.exists(tmp.resolve("old2.jsonl")));
        Session reloaded = store.load("old2").orElseThrow();
        assertEquals(2, reloaded.getMessages().size());
        assertEquals("新答复", reloaded.getMessages().get(1).getContent());
    }

    @Test
    void corruptLineSkippedWithoutCrash() throws Exception {
        SessionStore store = new SessionStore(tmp);
        store.save(newSession("c1", Instant.parse("2026-08-07T10:00:00Z"), 0, 0,
                new Message(Role.USER, "q1"), new Message(Role.ASSISTANT, "a1")));

        Path file = tmp.resolve("c1.jsonl");
        Files.writeString(file, Files.readString(file) + "this is not json\n");

        Session loaded = store.load("c1").orElseThrow();
        assertEquals(2, loaded.getMessages().size(), "损坏行跳过，其余消息保留");
        assertEquals("q1", loaded.getMessages().get(0).getContent());
    }

    @Test
    void toolResultTruncatedOnJsonlSave() throws Exception {
        SessionStore store = new SessionStore(tmp);
        String big = "y".repeat(70_000);
        Session s = newSession("c2", Instant.parse("2026-08-07T10:00:00Z"), 0, 0,
                new Message(Role.USER, List.of(
                        new com.zhubao.conversation.ContentBlock.ToolResultBlock("tu1", "bash", false, big)), null, null));
        store.save(s);

        Session loaded = store.load("c2").orElseThrow();
        String output = ((com.zhubao.conversation.ContentBlock.ToolResultBlock)
                loaded.getMessages().get(0).getBlocks().get(0)).output();
        assertTrue(output.length() <= SessionStore.TOOL_RESULT_PERSIST_CAP + 40);
        assertTrue(output.contains("已截断"));
    }

    @Test
    void loadMissingReturnsEmptyForJsonlToo() {
        SessionStore store = new SessionStore(tmp);
        assertEquals(Optional.empty(), store.load("nope"));
    }

    // ── M4 review-P1：历史收缩（压缩/snip）后重写，不残留陈旧消息 ──
    @Test
    void shrinkRewritesFileAndDropsStaleMessages() throws Exception {
        SessionStore store = new SessionStore(tmp);
        Instant t = Instant.parse("2026-08-07T10:00:00Z");
        List<Message> six = List.of(
                new Message(Role.USER, "u1"), new Message(Role.ASSISTANT, "a1"),
                new Message(Role.USER, "u2"), new Message(Role.ASSISTANT, "a2"),
                new Message(Role.USER, "u3"), new Message(Role.ASSISTANT, "a3"));
        store.save(newSession("sh1", t, 0, 0, six.toArray(new Message[0])));

        // 压缩：6 条 → 2 条
        List<Message> two = List.of(
                new Message(Role.USER, "【上下文已压缩】x"), new Message(Role.ASSISTANT, "a3"));
        store.save(newSession("sh1", t, 5, 6, two.toArray(new Message[0])));

        Session reloaded = store.load("sh1").orElseThrow();
        assertEquals(2, reloaded.getMessages().size(), "收缩后重载应只含压缩后的 2 条");
        assertEquals("【上下文已压缩】x", reloaded.getMessages().get(0).getContent());
        assertEquals(5, reloaded.getMeta().totalInputTokens());
        assertEquals(6, reloaded.getMeta().totalOutputTokens());

        // 继续追加不重复
        List<Message> three = List.of(
                new Message(Role.USER, "【上下文已压缩】x"), new Message(Role.ASSISTANT, "a3"),
                new Message(Role.USER, "u4"));
        store.save(newSession("sh1", t, 7, 8, three.toArray(new Message[0])));
        Session reloaded2 = store.load("sh1").orElseThrow();
        assertEquals(3, reloaded2.getMessages().size());
        assertEquals("u4", reloaded2.getMessages().get(2).getContent());
    }

    // ── M4 review-P3：.json/.jsonl 并存时 list() 去重且 .jsonl 优先 ──
    @Test
    void listDeduplicatesWhenJsonAndJsonlCoexist() throws Exception {
        SessionStore store = new SessionStore(tmp);
        Instant t = Instant.parse("2026-08-07T10:00:00Z");
        // 先写 .jsonl（新），再手工放一个旧 .json（模拟迁移崩溃窗口）
        store.save(newSession("dup1", t, 1, 2, new Message(Role.USER, "new")));
        String oldJson = """
                {"meta":{"id":"dup1","createdAt":"2026-08-07T09:00:00Z","updatedAt":"2026-08-07T09:00:00Z","title":"旧","messageCount":1,"provider":{"name":"deepseek","protocol":"openai","model":"deepseek-v4-flash","baseUrl":"http://x"}},"messages":[{"role":"user","content":"old"}]}
                """;
        Files.writeString(tmp.resolve("dup1.json"), oldJson);

        List<SessionMeta> metas = store.list();
        assertEquals(1, metas.size(), "同 id 双文件只应列出一条");
        assertEquals("dup1", metas.get(0).id());
        // .jsonl 优先（内容为 "new" 而非 "old"）
        Session loaded = store.load("dup1").orElseThrow();
        assertEquals("new", loaded.getMessages().get(0).getContent());
    }
}
