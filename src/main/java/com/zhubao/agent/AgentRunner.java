package com.zhubao.agent;

import com.zhubao.conversation.Conversation;
import com.zhubao.llm.ChatRequest;
import com.zhubao.llm.LlmClient;
import com.zhubao.llm.StreamEvent;
import com.zhubao.llm.ToolSpec;
import com.zhubao.tool.SerialToolExecutor;
import com.zhubao.tool.ToolCall;
import com.zhubao.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Agent 循环（M2，spec F1/F7）：一次用户输入 = 多步 LLM 调用 + 工具执行。
 *
 * <pre>
 * addUser → 步循环（≤ maxCallsPerTurn）:
 *   stream(buildRequest(system, tools)) → 消费事件（文本/思考/ToolCall）
 *   → 无工具调用或 end_turn → 本轮结束
 *   → 有工具调用 → 串行执行（权限确认）→ 一次性回填 assistant(全 toolUse)+user(全 toolResult) → 继续
 * </pre>
 *
 * 护栏：单步流空闲超时 120s（连续无事件才中断，宽容长思考）；步数上限防失控；
 * 整轮不设墙钟。与 TUI 解耦（AgentUi 回调），便于测试。
 */
public final class AgentRunner {

    /** 系统提示词：介绍 zhuCodeAgent 是可用工具的编码 agent */
    public static final String SYSTEM_PROMPT =
            "You are zhuCodeAgent, a command-line coding assistant. "
                    + "You can use tools to read, write and edit files, search the codebase, "
                    + "and run shell commands in the project workspace. "
                    + "Use tools when they help complete the task; answer concisely. "
                    + "Today's date is " + java.time.LocalDate.now() + ".";

    /** 单步流空闲超时：该步开始后连续无事件 N 毫秒 → 中断该步（spec F7） */
    public static final long STEP_IDLE_TIMEOUT_MS = 120_000;

    private static final long POLL_MS = 100;

    private AgentRunner() {
    }

    /** 一轮 agent 循环的结果 */
    public record Result(
            String text,
            String thinking,
            String signature,
            boolean error,
            String errorMessage,
            String stopReason,
            int inputTokens,
            int outputTokens,
            boolean limitReached) {
    }

    /**
     * 执行一轮 agent 循环。
     *
     * @param client         LLM 客户端
     * @param conversation   会话（追加用户消息与工具消息）
     * @param userText       用户输入
     * @param tools          工具定义（请求体注入）
     * @param executor       串行执行器（内含权限判定）
     * @param ui             UI 回调（可为 null，测试用）
     * @param maxCallsPerTurn 单轮工具调用上限（spec F7）
     */
    public static Result run(LlmClient client, Conversation conversation, String userText,
                             List<ToolSpec> tools, SerialToolExecutor executor,
                             AgentUi ui, int maxCallsPerTurn) {
        return run(client, conversation, userText, tools, executor, ui, maxCallsPerTurn, STEP_IDLE_TIMEOUT_MS);
    }

    /** 带可注入单步空闲超时（测试用短超时验证超时路径） */
    public static Result run(LlmClient client, Conversation conversation, String userText,
                             List<ToolSpec> tools, SerialToolExecutor executor,
                             AgentUi ui, int maxCallsPerTurn, long stepIdleTimeoutMs) {
        conversation.addUser(userText);
        int steps = 0;
        String stopReason = "";
        int inputTokens = 0;
        int outputTokens = 0;

        while (true) {
            if (steps >= maxCallsPerTurn) {
                return new Result("", "", null, false, null, stopReason, inputTokens, outputTokens, true);
            }
            if (ui != null) {
                ui.onStep("⏳ 正在思考…");
            }
            StepOutcome outcome = consumeStep(client, conversation, tools, ui, stepIdleTimeoutMs);
            if (outcome.errorMessage != null) {
                return new Result(outcome.text, outcome.thinking, outcome.signature, true,
                        outcome.errorMessage, outcome.stopReason, outcome.inputTokens, outcome.outputTokens, false);
            }
            stopReason = outcome.stopReason;
            inputTokens = outcome.inputTokens;
            outputTokens = outcome.outputTokens;
            if (outcome.toolCalls.isEmpty()) {
                // 无工具调用 → 本轮完成：最终 assistant 消息写回会话，输出最终文本
                conversation.addAssistant(outcome.text, outcome.thinking, outcome.signature);
                return new Result(outcome.text, outcome.thinking, outcome.signature, false,
                        null, stopReason, inputTokens, outputTokens, false);
            }
            // 有工具调用：串行执行（权限在 executor 内）→ 一次性回填
            List<ToolResult> results = executor.execute(outcome.toolCalls);
            conversation.addAssistantWithTools(outcome.text, outcome.thinking, outcome.signature, outcome.toolCalls);
            conversation.addToolResultBlocks(results);
            steps++;
        }
    }

    /** 一步：调 LLM、消费事件，返回该步文本/思考/工具调用与结束信息 */
    private static StepOutcome consumeStep(LlmClient client, Conversation conversation,
                                           List<ToolSpec> tools, AgentUi ui, long stepIdleTimeoutMs) {
        BlockingQueue<StreamEvent> queue =
                client.stream(conversation.buildRequest(SYSTEM_PROMPT, tools));
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        String signature = null;
        List<ToolCall> toolCalls = new ArrayList<>();
        String stopReason = "";
        int inputTokens = 0;
        int outputTokens = 0;
        long idleDeadline = System.currentTimeMillis() + stepIdleTimeoutMs;

        while (true) {
            long remaining = idleDeadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return new StepOutcome(text.toString(), thinking.toString(), signature, toolCalls,
                        stopReason, inputTokens, outputTokens, "生成超时（无响应）");
            }
            StreamEvent event;
            try {
                event = queue.poll(Math.min(POLL_MS, remaining), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new StepOutcome(text.toString(), thinking.toString(), signature, toolCalls,
                        stopReason, inputTokens, outputTokens, "生成被中断");
            }
            if (event == null) {
                continue;
            }
            if (ui != null) {
                ui.onEvent(event);
            }
            if (event instanceof StreamEvent.TextDelta td) {
                text.append(td.text());
            } else if (event instanceof StreamEvent.ThinkingDelta td) {
                thinking.append(td.text());
            } else if (event instanceof StreamEvent.ThinkingComplete tc) {
                signature = tc.signature();
            } else if (event instanceof StreamEvent.ToolCall tc) {
                toolCalls.add(new ToolCall(tc.id(), tc.name(), tc.argumentsJson()));
            } else if (event instanceof StreamEvent.Error err) {
                return new StepOutcome(text.toString(), thinking.toString(), signature, toolCalls,
                        stopReason, inputTokens, outputTokens, err.message());
            } else if (event instanceof StreamEvent.StreamEnd end) {
                return new StepOutcome(text.toString(), thinking.toString(), signature, toolCalls,
                        end.stopReason(), end.inputTokens(), end.outputTokens(), null);
            }
        }
    }

    /** 一步的累积结果 */
    private record StepOutcome(
            String text,
            String thinking,
            String signature,
            List<ToolCall> toolCalls,
            String stopReason,
            int inputTokens,
            int outputTokens,
            String errorMessage) {
    }
}
