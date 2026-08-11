package com.zhubao.context;

import com.zhubao.agent.AgentUi;
import com.zhubao.conversation.ContentBlock;
import com.zhubao.conversation.Conversation;
import com.zhubao.conversation.Message;
import com.zhubao.conversation.Role;
import com.zhubao.llm.ChatRequest;
import com.zhubao.llm.LlmClient;
import com.zhubao.llm.LlmStream;
import com.zhubao.llm.StreamEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 双层渐进压缩（M4，spec F3）：
 * <ol>
 *   <li>本地零成本瘦身：丢弃「空结果 / 被拒绝」的低价值 tool 轮次；截断超长 tool_result。</li>
 *   <li>LLM 摘要折叠：把最旧 N 轮折叠为带「【上下文已压缩】」标记的 user 摘要，并合并到
 *       最近段首条真实 user 消息（避免连续 user 消息，保护双协议回填，spec N3）。</li>
 * </ol>
 *
 * <p>熔断：连续 3 次压缩失败 → 自动压缩停用（{@link #isAutoDisabled()}），手动 /compact 不受限。
 * 占用复核：压缩效果由下一次请求的 API usage 复核（不引入本地 tokenizer，spec N1）。
 */
public final class ContextCompactor {

    /** 摘要消息前缀标记（spec F3） */
    public static final String SUMMARY_PREFIX = "【上下文已压缩】";
    private static final String SUMMARY_SYSTEM_PROMPT =
            "You are compressing a coding-agent conversation history. "
                    + "Summarize the following conversation turns into a concise summary (aim under 500 tokens). "
                    + "Preserve: key decisions, file paths created or modified, important tool outputs, "
                    + "and the current task state. Do not invent new information. Output only the summary text.";
    private static final long SUMMARY_POLL_MS = 100;
    private static final long SUMMARY_IDLE_TIMEOUT_MS = 60_000;
    private static final int BREAKER_LIMIT = 3;

    private int consecutiveFailures = 0;
    private boolean autoDisabled = false;

    public boolean isAutoDisabled() {
        return autoDisabled;
    }

    /**
     * 执行压缩（直接改写 conversation 历史）。
     *
     * @param ui 进度回调（可空）
     */
    public CompactionResult compact(Conversation conversation, LlmClient client, CompactionOptions opts, AgentUi ui) {
        if (ui != null) {
            ui.onStep("📦 正在压缩上下文…");
        }
        List<Message> messages = conversation.getMessages();

        // ① 本地零成本瘦身
        List<Message> slimmed = opts.snipEnabled() ? snip(messages) : new ArrayList<>(messages);
        // snip 只整对（assistant+tool_result）丢弃 → 消息数差/2 即丢弃轮数
        int dropped = (messages.size() - slimmed.size()) / 2;
        int truncated = truncate(slimmed, opts.toolResultCap());

        // ② 摘要折叠
        FoldPlan plan = planFold(slimmed, opts.keepRecentTurns());
        if (!plan.needsFold()) {
            if (dropped == 0 && truncated == 0) {
                return CompactionResult.none();
            }
            conversation.replaceMessages(slimmed);
            resetBreaker();
            return CompactionResult.ok(0, dropped, truncated, "");
        }

        String summary;
        try {
            summary = summarize(client, plan.folded());
        } catch (Exception e) {
            return fail("压缩失败: " + safeMessage(e));
        }
        if (summary == null || summary.isBlank()) {
            return fail("压缩失败: 摘要为空");
        }
        resetBreaker();
        conversation.replaceMessages(rebuild(plan, summary));
        return CompactionResult.ok(plan.foldedTurns(), dropped, truncated, summary);
    }

    // ── ① 本地瘦身 ──────────────────────────────────────────

    /** 丢弃「assistant(tool_use) + 随后 user(纯 tool_result)」且全部结果为低价值的配对 */
    static List<Message> snip(List<Message> messages) {
        List<Message> out = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (i + 1 < messages.size()
                    && m.getRole() == Role.ASSISTANT
                    && hasToolUses(m)
                    && isLowValuePair(m, messages.get(i + 1))) {
                i++; // 跳过配对的 tool_result 消息
                continue;
            }
            out.add(m);
        }
        return out;
    }

    private static boolean isLowValuePair(Message assistant, Message user) {
        if (user.getRole() != Role.USER || user.getBlocks().isEmpty()) {
            return false;
        }
        boolean onlyResults = true;
        boolean allLowValue = true;
        for (ContentBlock b : user.getBlocks()) {
            if (!(b instanceof ContentBlock.ToolResultBlock tr)) {
                onlyResults = false;
                break;
            }
            if (!lowValue(tr)) {
                allLowValue = false;
            }
        }
        return onlyResults && allLowValue;
    }

    private static boolean lowValue(ContentBlock.ToolResultBlock tr) {
        String out = tr.output();
        if (out == null || out.isBlank()) {
            return true;
        }
        // 覆盖「已拒绝执行（用户选择/权限禁止）」与 BashTool 的「危险命令已拒绝（…）」
        return out.contains("已拒绝") || out.contains("权限禁止");
    }

    /** 把超限 tool_result 输出截断（内存视图，与落盘截断口径一致） */
    static int truncate(List<Message> messages, int cap) {
        int count = 0;
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            List<ContentBlock> blocks = m.getBlocks();
            List<ContentBlock> newBlocks = null;
            for (int j = 0; j < blocks.size(); j++) {
                ContentBlock b = blocks.get(j);
                if (b instanceof ContentBlock.ToolResultBlock tr
                        && tr.output() != null && tr.output().length() > cap) {
                    if (newBlocks == null) {
                        newBlocks = new ArrayList<>(blocks);
                    }
                    String truncated = tr.output().substring(0, cap)
                            + "\n…（已压缩截断，共 " + tr.output().length() + " 字节）";
                    newBlocks.set(j, new ContentBlock.ToolResultBlock(tr.id(), tr.name(), tr.isError(), truncated));
                    count++;
                }
            }
            if (newBlocks != null) {
                messages.set(i, new Message(m.getRole(), newBlocks, m.getThinking(), m.getThinkingSignature()));
            }
        }
        return count;
    }

    // ── ② 摘要折叠 ──────────────────────────────────────────

    /** 折叠计划：folded = 最旧轮次；recent = 保留的最近 keepRecentTurns 轮 */
    private static FoldPlan planFold(List<Message> messages, int keepRecentTurns) {
        int realUserSeen = 0;
        int boundary = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.getRole() == Role.USER && !hasToolResults(m)) {
                realUserSeen++;
                if (realUserSeen == keepRecentTurns) {
                    boundary = i;
                    break;
                }
            }
        }
        if (boundary <= 0) {
            return FoldPlan.none();
        }
        int foldedTurns = 0;
        for (int i = 0; i < boundary; i++) {
            Message m = messages.get(i);
            if (m.getRole() == Role.USER && !hasToolResults(m)) {
                foldedTurns++;
            }
        }
        return new FoldPlan(List.copyOf(messages.subList(0, boundary)),
                List.copyOf(messages.subList(boundary, messages.size())), foldedTurns);
    }

    /** 调模型生成摘要（无工具、单次调用） */
    private static String summarize(LlmClient client, List<Message> folded) {
        LlmStream stream = client.stream(new ChatRequest(SUMMARY_SYSTEM_PROMPT, folded));
        BlockingQueue<StreamEvent> queue = stream.events();
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + SUMMARY_IDLE_TIMEOUT_MS;
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }
            StreamEvent ev;
            try {
                ev = queue.poll(Math.min(SUMMARY_POLL_MS, remaining), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("摘要生成被中断", e);
            }
            if (ev == null) {
                continue;
            }
            if (ev instanceof StreamEvent.TextDelta td) {
                sb.append(td.text());
            } else if (ev instanceof StreamEvent.Error err) {
                throw new IllegalStateException(err.message());
            } else if (ev instanceof StreamEvent.StreamEnd) {
                break;
            }
        }
        return sb.toString().trim();
    }

    /** 重组历史：摘要并入最近段首条真实 user 消息，避免连续 user（双协议回填安全） */
    private static List<Message> rebuild(FoldPlan plan, String summary) {
        String summaryText = SUMMARY_PREFIX + "\n" + summary;
        List<Message> out = new ArrayList<>();
        if (plan.recent().isEmpty()) {
            out.add(new Message(Role.USER, summaryText));
            return out;
        }
        Message first = plan.recent().get(0);
        if (first.getRole() == Role.USER && !hasToolResults(first)) {
            out.add(new Message(Role.USER, summaryText + "\n\n" + first.getContent()));
            out.addAll(plan.recent().subList(1, plan.recent().size()));
        } else {
            out.add(new Message(Role.USER, summaryText));
            out.addAll(plan.recent());
        }
        return out;
    }

    // ── 工具方法 ─────────────────────────────────────────────

    private CompactionResult fail(String message) {
        consecutiveFailures++;
        if (consecutiveFailures >= BREAKER_LIMIT) {
            autoDisabled = true;
        }
        return CompactionResult.error(message);
    }

    private void resetBreaker() {
        consecutiveFailures = 0;
    }

    private static boolean hasToolUses(Message m) {
        return m.getBlocks().stream().anyMatch(ContentBlock.ToolUseBlock.class::isInstance);
    }

    private static boolean hasToolResults(Message m) {
        return m.getBlocks().stream().anyMatch(ContentBlock.ToolResultBlock.class::isInstance);
    }

    private static String safeMessage(Exception e) {
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
    }

    /** 折叠计划（folded 为空时表示无需折叠） */
    private record FoldPlan(List<Message> folded, List<Message> recent, int foldedTurns) {

        static FoldPlan none() {
            return new FoldPlan(List.of(), List.of(), 0);
        }

        boolean needsFold() {
            return !folded.isEmpty();
        }
    }
}
