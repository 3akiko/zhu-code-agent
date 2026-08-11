package com.zhubao.context;

import com.zhubao.conversation.ContentBlock;
import com.zhubao.conversation.Conversation;
import com.zhubao.conversation.Message;
import com.zhubao.conversation.Role;
import com.zhubao.llm.ChatRequest;
import com.zhubao.llm.LlmClient;
import com.zhubao.llm.LlmStream;
import com.zhubao.llm.StreamEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 双层渐进压缩单测（M4，spec F3）：snip / 截断 / 摘要折叠 / 熔断。
 */
class ContextCompactorTest {

    private static final class StubClient implements LlmClient {
        private final Queue<List<StreamEvent>> scripts = new ArrayDeque<>();

        StubClient(List<List<StreamEvent>> scripts) {
            this.scripts.addAll(scripts);
        }

        @Override
        public LlmStream stream(ChatRequest request) {
            List<StreamEvent> script = scripts.isEmpty()
                    ? List.of(new StreamEvent.StreamEnd("end_turn", 0, 0))
                    : scripts.poll();
            return new LlmStream(new LinkedBlockingQueue<>(script), () -> { }, () -> { });
        }
    }

    private static Message user(String text) {
        return new Message(Role.USER, text);
    }

    private static Message assistantText(String text) {
        return new Message(Role.ASSISTANT, text);
    }

    private static Message assistantToolUse(String id, String name) {
        return new Message(Role.ASSISTANT, List.of(new ContentBlock.ToolUseBlock(id, name, "{}")), null, null);
    }

    private static Message toolResult(String id, boolean error, String output) {
        return new Message(Role.USER, List.of(new ContentBlock.ToolResultBlock(id, "read_file", error, output)), null, null);
    }

    @Test
    void snipDropsEmptyAndRejectedToolPairsKeepsNormal() {
        // 一轮真实结构：U1 → A1(tool_use) → R1(空结果) → A2(tool_use) → R2(正常) → A3(结束文本)
        Conversation conv = new Conversation(List.of(
                user("q1"),
                assistantToolUse("t1", "read_file"),
                toolResult("t1", false, ""),                       // 空结果 → 低价值
                assistantToolUse("t2", "read_file"),
                toolResult("t2", false, "file content"),            // 正常 → 保留
                assistantText("done")));
        ContextCompactor compactor = new ContextCompactor();
        CompactionResult r = compactor.compact(conv, new StubClient(List.of()),
                new CompactionOptions(0.6, true, 8, 1024), null);
        assertFalse(r.isError(), r.errorMessage());
        assertTrue(r.compacted());
        assertEquals(1, r.droppedTurns());
        assertEquals(0, r.foldedTurns());
        List<Message> msgs = conv.getMessages();
        assertEquals(4, msgs.size());
        // U1 后紧跟 A2（工具轮配对被整对移除，user/assistant 交替不破）
        assertEquals(Role.USER, msgs.get(0).getRole());
        assertEquals(Role.ASSISTANT, msgs.get(1).getRole());
        assertEquals("file content", ((ContentBlock.ToolResultBlock) msgs.get(2).getBlocks().get(0)).output());
        assertEquals("done", msgs.get(3).getContent());
    }

    @Test
    void snipDropsDangerRejectedBashPair() {
        // BashTool 拒绝文案是「危险命令已拒绝（…）」（M4 review-P3 回归）
        Conversation conv = new Conversation(List.of(
                user("q1"),
                assistantToolUse("t1", "bash"),
                new Message(Role.USER, List.of(new ContentBlock.ToolResultBlock("t1", "bash", true,
                        "危险命令已拒绝（目标不在工作区…）")), null, null),
                assistantText("done")));
        ContextCompactor compactor = new ContextCompactor();
        CompactionResult r = compactor.compact(conv, new StubClient(List.of()),
                new CompactionOptions(0.6, true, 8, 1024), null);
        assertFalse(r.isError(), r.errorMessage());
        assertEquals(1, r.droppedTurns());
        assertEquals(2, conv.getMessages().size());
        assertEquals(Role.USER, conv.getMessages().get(0).getRole());
        assertEquals(Role.ASSISTANT, conv.getMessages().get(1).getRole());
    }

    @Test
    void truncateOversizedToolResultWithAnnotation() {
        String big = "x".repeat(100);
        Conversation conv = new Conversation(List.of(
                user("q1"),
                assistantToolUse("t1", "read_file"),
                toolResult("t1", false, big)));
        ContextCompactor compactor = new ContextCompactor();
        // cap=20 → 截断
        CompactionResult r = compactor.compact(conv, new StubClient(List.of()),
                new CompactionOptions(0.6, false, 8, 20), null);
        assertFalse(r.isError());
        assertEquals(1, r.truncatedResults());
        ContentBlock.ToolResultBlock tr = (ContentBlock.ToolResultBlock) conv.getMessages().get(2).getBlocks().get(0);
        assertTrue(tr.output().startsWith("x".repeat(20) + "\n…（已压缩截断，共 100 字节）"));
        assertTrue(tr.output().length() < 100);
    }

    @Test
    void foldOldestTurnsIntoSummaryMergedIntoFirstRecentUser() {
        Conversation conv = new Conversation(List.of(
                user("q1"),
                assistantToolUse("t1", "read_file"),
                toolResult("t1", false, "a.txt 内容"),
                assistantText("第一轮答复"),
                user("q2"),
                assistantText("第二轮答复")));
        StubClient client = new StubClient(List.of(
                List.of(new StreamEvent.TextDelta("摘要：q1 轮次要点"), new StreamEvent.StreamEnd("end_turn", 1, 2))));
        ContextCompactor compactor = new ContextCompactor();
        CompactionResult r = compactor.compact(conv, client, new CompactionOptions(0.6, true, 1, 1024), null);
        assertFalse(r.isError(), r.errorMessage());
        assertTrue(r.compacted());
        assertEquals(1, r.foldedTurns());
        assertTrue(r.summary().contains("摘要：q1 轮次要点"));
        List<Message> msgs = conv.getMessages();
        assertEquals(2, msgs.size());
        // 摘要并入 q2 首条真实 user 消息（无连续 user，交替保持）
        assertEquals(Role.USER, msgs.get(0).getRole());
        assertTrue(msgs.get(0).getContent().startsWith("【上下文已压缩】"));
        assertTrue(msgs.get(0).getContent().contains("q2"));
        assertEquals(Role.ASSISTANT, msgs.get(1).getRole());
    }

    @Test
    void nothingToCompactReturnsNone() {
        Conversation conv = new Conversation(List.of(user("only")));
        ContextCompactor compactor = new ContextCompactor();
        CompactionResult r = compactor.compact(conv, new StubClient(List.of()),
                new CompactionOptions(0.6, true, 8, 1024), null);
        assertFalse(r.compacted());
        assertFalse(r.isError());
        assertEquals(1, conv.getMessages().size());
    }

    @Test
    void breakerDisablesAutoAfterThreeFailures() {
        Conversation conv = new Conversation(List.of(
                user("q1"),
                assistantToolUse("t1", "read_file"),
                toolResult("t1", false, "内容"),
                assistantText("答复"),
                user("q2")));
        StubClient failing = new StubClient(List.of(
                List.of(new StreamEvent.Error("boom")),
                List.of(new StreamEvent.Error("boom")),
                List.of(new StreamEvent.Error("boom"))));
        ContextCompactor compactor = new ContextCompactor();
        CompactionOptions opts = new CompactionOptions(0.6, true, 1, 1024);

        CompactionResult r1 = compactor.compact(conv, failing, opts, null);
        assertTrue(r1.isError());
        assertFalse(compactor.isAutoDisabled());

        CompactionResult r2 = compactor.compact(conv, failing, opts, null);
        assertTrue(r2.isError());

        CompactionResult r3 = compactor.compact(conv, failing, opts, null);
        assertTrue(r3.isError());
        assertTrue(compactor.isAutoDisabled(), "连续 3 次失败后自动压缩应熔断");

        // 会话未被失败路径改写
        assertTrue(conv.getMessages().stream().anyMatch(m -> m.getContent() != null && m.getContent().contains("q2")));
    }

    @Test
    void successResetsBreaker() {
        Conversation conv = new Conversation(List.of(
                user("q1"),
                assistantToolUse("t1", "read_file"),
                toolResult("t1", false, "内容"),
                assistantText("答复"),
                user("q2")));
        ContextCompactor compactor = new ContextCompactor();
        CompactionOptions opts = new CompactionOptions(0.6, true, 1, 1024);

        // 2 次失败（未熔断），随后 1 次成功 → 计数复位，之后失败也不会因旧计数熔断
        compactor.compact(conv, new StubClient(List.of(
                List.of(new StreamEvent.Error("boom")),
                List.of(new StreamEvent.Error("boom")))), opts, null);
        CompactionResult ok = compactor.compact(conv, new StubClient(List.of(
                List.of(new StreamEvent.TextDelta("ok"), new StreamEvent.StreamEnd("end_turn", 1, 1)))), opts, null);
        assertFalse(ok.isError());
        assertFalse(compactor.isAutoDisabled());
    }
}
