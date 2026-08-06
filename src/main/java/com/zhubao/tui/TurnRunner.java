package com.zhubao.tui;

import com.zhubao.conversation.Conversation;
import com.zhubao.llm.LlmClient;
import com.zhubao.llm.StreamEvent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 单轮对话核心逻辑（与 TUI 解耦，便于单元测试与集成测试）。
 *
 * <p>流程：把用户消息加入会话 → 调 LLM 流式请求 → 消费事件队列，累积正文/思考/
 * signature，直到 StreamEnd 或 Error。增量事件通过 onEvent 回调实时暴露给 UI 渲染
 * （spec F3/F7：到达即显示、思考灰字与正文分离）。
 */
public final class TurnRunner {

    /** 系统提示词：介绍 zhuCodeAgent 并要求简洁回复 */
    public static final String SYSTEM_PROMPT =
            "You are zhuCodeAgent, a command-line coding assistant. "
                    + "Answer the user's questions concisely and helpfully. "
                    + "Today's date is " + java.time.LocalDate.now() + ".";

    private static final long POLL_TIMEOUT_MS = 100;
    private static final long TURN_DEADLINE_MS = 120_000;

    /** 一轮对话的结果 */
    public record Result(
            String text,
            String thinking,
            String signature,
            boolean error,
            String errorMessage,
            String stopReason,
            int inputTokens,
            int outputTokens) {
    }

    private TurnRunner() {
    }

    /**
     * 执行一轮对话。
     *
     * @param client     LLM 客户端
     * @param conversation 会话（会被追加用户消息）
     * @param userText   用户输入
     * @param onEvent    增量事件回调（可为 null；UI 用它实时渲染）
     */
    public static Result run(LlmClient client, Conversation conversation, String userText, Consumer<StreamEvent> onEvent) {
        conversation.addUser(userText);
        BlockingQueue<StreamEvent> queue = client.stream(conversation.buildRequest(SYSTEM_PROMPT));

        StringBuilder answer = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        String signature = null;
        String errorMessage = null;
        String stopReason = "";
        int inputTokens = 0;
        int outputTokens = 0;
        long deadline = System.currentTimeMillis() + TURN_DEADLINE_MS;

        while (true) {
            if (System.currentTimeMillis() > deadline) {
                return new Result(answer.toString(), thinking.toString(), signature, true,
                        "生成超时（超过 " + TURN_DEADLINE_MS / 1000 + " 秒）", stopReason, inputTokens, outputTokens);
            }
            StreamEvent event;
            try {
                event = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result(answer.toString(), thinking.toString(), signature, true,
                        "生成被中断", stopReason, inputTokens, outputTokens);
            }
            if (event == null) {
                continue;
            }
            if (onEvent != null) {
                onEvent.accept(event);
            }
            if (event instanceof StreamEvent.TextDelta td) {
                answer.append(td.text());
            } else if (event instanceof StreamEvent.ThinkingDelta td) {
                thinking.append(td.text());
            } else if (event instanceof StreamEvent.ThinkingComplete tc) {
                signature = tc.signature();
            } else if (event instanceof StreamEvent.Error err) {
                return new Result(answer.toString(), thinking.toString(), signature, true,
                        err.message(), stopReason, inputTokens, outputTokens);
            } else if (event instanceof StreamEvent.StreamEnd end) {
                return new Result(answer.toString(), thinking.toString(), signature, false,
                        null, end.stopReason(), end.inputTokens(), end.outputTokens());
            }
        }
    }
}
