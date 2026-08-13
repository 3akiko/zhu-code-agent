package com.zhubao.agent;

import com.zhubao.conversation.Conversation;
import com.zhubao.llm.ChatRequest;
import com.zhubao.llm.LlmClient;
import com.zhubao.llm.LlmStream;
import com.zhubao.llm.StreamEvent;
import com.zhubao.llm.ToolSpec;
import com.zhubao.tool.PlanModeExecutor;
import com.zhubao.tool.SerialToolExecutor;
import com.zhubao.tool.ToolCall;
import com.zhubao.tool.ToolExecutor;
import com.zhubao.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Agent 循环（M2，spec F1/F7；M3 扩展 runPlan/runPlanContinue/runExecution，spec F2）：
 * 一次用户输入 = 多步 LLM 调用 + 工具执行。
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
 *
 * M3 /plan：计划阶段用 PLAN_SYSTEM_PROMPT + {@link PlanModeExecutor}（只读调研，
 * 模型 end_turn 即计划完成，最终文本即计划）；批准后 runExecution 不再重复 addUser；
 * 修改意见后 runPlanContinue 同样不重复 addUser。
 */
public final class AgentRunner {

    /** 系统提示词：介绍 zhuCodeAgent 是可用工具的编码 agent */
    public static final String SYSTEM_PROMPT =
            "You are zhuCodeAgent, a command-line coding assistant. "
                    + "You can use tools to read, write and edit files, search the codebase, "
                    + "and run shell commands in the project workspace. "
                    + "Use tools when they help complete the task; answer concisely. "
                    + "Today's date is " + java.time.LocalDate.now() + ".";

    /** 计划模式系统提示词（M3，spec F2）：只读调研后输出分步计划并结束 */
    public static final String PLAN_SYSTEM_PROMPT =
            "You are zhuCodeAgent in PLAN MODE. The user wants a plan BEFORE any changes are made. "
                    + "You may use read-only tools (read_file, grep, glob) to research the codebase. "
                    + "Do NOT call write_file, edit_file, or bash — they are blocked in plan mode. "
                    + "When you have finished researching, output the execution plan as your final text: "
                    + "a concise step-by-step list of the changes you will make and why. "
                    + "Then stop (end_turn). Today's date is " + java.time.LocalDate.now() + ".";

    /** 单步流空闲超时：该步开始后连续无事件 N 毫秒 → 中断该步（spec F7） */
    public static final long STEP_IDLE_TIMEOUT_MS = 120_000;

    private static final long POLL_MS = 100;

    private AgentRunner() {
    }

    /**
     * 一轮 agent 循环的结果。
     *
     * <p>M4（spec F1/F5）：totalInputTokens/totalOutputTokens 为本轮全部步骤求和；
     * cacheReadTokens/cacheCreationTokens 为最后一步的 prompt 缓存命中（spec F4）；
     * interrupted 表示被 Ctrl+C 中断（半成品不写入会话、不追加进行中那轮 tool_use/tool_result）。
     */
    public record Result(
            String text,
            String thinking,
            String signature,
            boolean error,
            String errorMessage,
            String stopReason,
            int inputTokens,
            int outputTokens,
            boolean limitReached,
            int totalInputTokens,
            int totalOutputTokens,
            int cacheReadTokens,
            int cacheCreationTokens,
            boolean interrupted) {

        /** 兼容旧调用（M1–M3）：累计/cache=0、interrupted=false */
        public Result(String text, String thinking, String signature, boolean error, String errorMessage,
                      String stopReason, int inputTokens, int outputTokens, boolean limitReached) {
            this(text, thinking, signature, error, errorMessage, stopReason, inputTokens, outputTokens,
                    limitReached, inputTokens, outputTokens, 0, 0, false);
        }
    }

    /** 普通一轮（addUser + NORMAL 提示词）；M5：executor 放宽为 {@link ToolExecutor}（串行/并行可插拔） */
    public static Result run(LlmClient client, Conversation conversation, String userText,
                             List<ToolSpec> tools, ToolExecutor executor,
                             AgentUi ui, int maxCallsPerTurn) {
        return run(client, conversation, userText, tools, executor, ui, maxCallsPerTurn, STEP_IDLE_TIMEOUT_MS);
    }

    /** 带可注入单步空闲超时（测试用短超时验证超时路径） */
    public static Result run(LlmClient client, Conversation conversation, String userText,
                             List<ToolSpec> tools, ToolExecutor executor,
                             AgentUi ui, int maxCallsPerTurn, long stepIdleTimeoutMs) {
        return run(client, conversation, userText, tools, executor, ui, maxCallsPerTurn,
                stepIdleTimeoutMs, null);
    }

    /** M4（spec F5）：普通一轮 + 流句柄回调（ChatApp 用于 Ctrl+C 取消与退出 join） */
    public static Result run(LlmClient client, Conversation conversation, String userText,
                             List<ToolSpec> tools, ToolExecutor executor, AgentUi ui,
                             int maxCallsPerTurn, long stepIdleTimeoutMs, Consumer<LlmStream> onStream) {
        return runLoop(client, conversation, userText, tools, executor, ui,
                maxCallsPerTurn, stepIdleTimeoutMs, SYSTEM_PROMPT, true, onStream);
    }

    /** /plan 计划阶段：addUser + PLAN 提示词 + 只读受限执行器（spec F2） */
    public static Result runPlan(LlmClient client, Conversation conversation, String userText,
                                 List<ToolSpec> tools, PlanModeExecutor executor, AgentUi ui,
                                 int maxCallsPerTurn, long stepIdleTimeoutMs) {
        return runPlan(client, conversation, userText, tools, executor, ui,
                maxCallsPerTurn, stepIdleTimeoutMs, null);
    }

    /** M4（spec F5）：计划阶段 + 流句柄回调 */
    public static Result runPlan(LlmClient client, Conversation conversation, String userText,
                                 List<ToolSpec> tools, PlanModeExecutor executor, AgentUi ui,
                                 int maxCallsPerTurn, long stepIdleTimeoutMs, Consumer<LlmStream> onStream) {
        return runLoop(client, conversation, userText, tools, executor, ui,
                maxCallsPerTurn, stepIdleTimeoutMs, PLAN_SYSTEM_PROMPT, true, onStream);
    }

    /** 修改意见后重新生成计划：不再 addUser（会话已含任务+调研+旧计划+意见） */
    public static Result runPlanContinue(LlmClient client, Conversation conversation,
                                         List<ToolSpec> tools, PlanModeExecutor executor, AgentUi ui,
                                         int maxCallsPerTurn, long stepIdleTimeoutMs) {
        return runPlanContinue(client, conversation, tools, executor, ui,
                maxCallsPerTurn, stepIdleTimeoutMs, null);
    }

    /** M4（spec F5）：重新生成计划 + 流句柄回调 */
    public static Result runPlanContinue(LlmClient client, Conversation conversation,
                                         List<ToolSpec> tools, PlanModeExecutor executor, AgentUi ui,
                                         int maxCallsPerTurn, long stepIdleTimeoutMs, Consumer<LlmStream> onStream) {
        return runLoop(client, conversation, null, tools, executor, ui,
                maxCallsPerTurn, stepIdleTimeoutMs, PLAN_SYSTEM_PROMPT, false, onStream);
    }

    /** 批准后执行阶段：不再 addUser（会话已含 user+计划+调研结果），正常循环；M5：executor 放宽 ToolExecutor */
    public static Result runExecution(LlmClient client, Conversation conversation,
                                      List<ToolSpec> tools, ToolExecutor executor, AgentUi ui,
                                      int maxCallsPerTurn, long stepIdleTimeoutMs) {
        return runExecution(client, conversation, tools, executor, ui,
                maxCallsPerTurn, stepIdleTimeoutMs, null);
    }

    /** M4（spec F5）：执行阶段 + 流句柄回调 */
    public static Result runExecution(LlmClient client, Conversation conversation,
                                      List<ToolSpec> tools, ToolExecutor executor, AgentUi ui,
                                      int maxCallsPerTurn, long stepIdleTimeoutMs, Consumer<LlmStream> onStream) {
        return runLoop(client, conversation, null, tools, executor, ui,
                maxCallsPerTurn, stepIdleTimeoutMs, SYSTEM_PROMPT, false, onStream);
    }

    // ── M5（spec F3）：子任务执行 ─────────────────────────────

    /**
     * 子任务系统提示词：聚焦任务、直接执行工具、不询问用户、最终以简洁报告收尾。
     * 子任务在独立会话中运行（看不到父历史），结果以摘要回填父 agent（F3.6）。
     */
    public static final String SUBTASK_SYSTEM_PROMPT =
            "You are a sub-agent of zhuCodeAgent, working on an isolated task with your own context. "
                    + "Complete the assigned task directly using the available tools. "
                    + "Do NOT ask the user questions; do NOT chat. "
                    + "Focus strictly on the task scope. "
                    + "When done, output a concise final report summarizing what you did and the key results.";

    /**
     * 运行一个子任务（M5，spec F3.2/F3.10）：独立 Conversation（任务描述已由调用方 addUser），
     * 复用 runLoop，maxCallsPerTurn = 子任务步数上限（独立护栏，不计父计数）。
     */
    public static Result runSubtask(LlmClient client, Conversation conversation,
                                    List<ToolSpec> tools, ToolExecutor executor, AgentUi ui,
                                    int maxSteps, long stepIdleTimeoutMs, Consumer<LlmStream> onStream) {
        return runLoop(client, conversation, null, tools, executor, ui,
                maxSteps, stepIdleTimeoutMs, SUBTASK_SYSTEM_PROMPT, false, onStream);
    }

    /**
     * 统一循环实现。
     *
     * @param addUser true 时先 conversation.addUser(userText)（普通 run 与首轮 runPlan）；
     *                false 表示用户消息已存在（runExecution / runPlanContinue）
     */
    private static Result runLoop(LlmClient client, Conversation conversation, String userText,
                                  List<ToolSpec> tools, ToolExecutor executor, AgentUi ui,
                                  int maxCallsPerTurn, long stepIdleTimeoutMs,
                                  String systemPrompt, boolean addUser, Consumer<LlmStream> onStream) {
        if (addUser) {
            conversation.addUser(userText);
        }
        int steps = 0;
        String stopReason = "";
        int inputTokens = 0;
        int outputTokens = 0;
        // M4（spec F1/F4/F5）：本轮累计与最后一步缓存命中
        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        int cacheReadTokens = 0;
        int cacheCreationTokens = 0;

        while (true) {
            if (steps >= maxCallsPerTurn) {
                return new Result("", "", null, false, null, stopReason, inputTokens, outputTokens, true,
                        totalInputTokens, totalOutputTokens, cacheReadTokens, cacheCreationTokens, false);
            }
            if (ui != null) {
                ui.onStep("⏳ 正在思考…");
            }
            StepOutcome outcome = consumeStep(client, conversation, tools, systemPrompt, ui, stepIdleTimeoutMs, onStream);
            totalInputTokens += outcome.inputTokens;
            totalOutputTokens += outcome.outputTokens;
            cacheReadTokens = outcome.cacheReadTokens;
            cacheCreationTokens = outcome.cacheCreationTokens;
            if (outcome.interrupted) {
                // 生成被 Ctrl+C 中断：半成品不写回会话、不执行未决工具调用
                return new Result(outcome.text, outcome.thinking, outcome.signature, true,
                        outcome.errorMessage, outcome.stopReason, outcome.inputTokens, outcome.outputTokens, false,
                        totalInputTokens, totalOutputTokens, cacheReadTokens, cacheCreationTokens, true);
            }
            if (outcome.errorMessage != null) {
                return new Result(outcome.text, outcome.thinking, outcome.signature, true,
                        outcome.errorMessage, outcome.stopReason, outcome.inputTokens, outcome.outputTokens, false,
                        totalInputTokens, totalOutputTokens, cacheReadTokens, cacheCreationTokens, false);
            }
            stopReason = outcome.stopReason;
            inputTokens = outcome.inputTokens;
            outputTokens = outcome.outputTokens;
            if (outcome.toolCalls.isEmpty()) {
                // 无工具调用 → 本轮完成：最终 assistant 消息写回会话，输出最终文本
                conversation.addAssistant(outcome.text, outcome.thinking, outcome.signature);
                return new Result(outcome.text, outcome.thinking, outcome.signature, false,
                        null, stopReason, inputTokens, outputTokens, false,
                        totalInputTokens, totalOutputTokens, cacheReadTokens, cacheCreationTokens, false);
            }
            // 有工具调用：串行执行（权限在 executor 内）→ 一次性回填
            List<ToolResult> results = executor.execute(outcome.toolCalls);
            if (Thread.currentThread().isInterrupted()) {
                // 工具执行中被 Ctrl+C 中断：不追加进行中那轮的 tool_use/tool_result（避免悬空 tool_use，M4 变更控制）
                return new Result("", "", null, true, "已中断", stopReason, inputTokens, outputTokens, false,
                        totalInputTokens, totalOutputTokens, cacheReadTokens, cacheCreationTokens, true);
            }
            conversation.addAssistantWithTools(outcome.text, outcome.thinking, outcome.signature, outcome.toolCalls);
            conversation.addToolResultBlocks(results);
            steps++;
        }
    }

    /** 一步：调 LLM、消费事件，返回该步文本/思考/工具调用与结束信息 */
    private static StepOutcome consumeStep(LlmClient client, Conversation conversation,
                                           List<ToolSpec> tools, String systemPrompt,
                                           AgentUi ui, long stepIdleTimeoutMs, Consumer<LlmStream> onStream) {
        LlmStream stream = client.stream(conversation.buildRequest(systemPrompt, tools));
        if (onStream != null) {
            onStream.accept(stream);
        }
        BlockingQueue<StreamEvent> queue = stream.events();
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
                        stopReason, inputTokens, outputTokens, 0, 0, true, "生成被中断");
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
                boolean interrupted = "已中断".equals(err.message());
                return new StepOutcome(text.toString(), thinking.toString(), signature, toolCalls,
                        stopReason, inputTokens, outputTokens, 0, 0, interrupted, err.message());
            } else if (event instanceof StreamEvent.StreamEnd end) {
                return new StepOutcome(text.toString(), thinking.toString(), signature, toolCalls,
                        end.stopReason(), end.inputTokens(), end.outputTokens(),
                        end.cacheReadTokens(), end.cacheCreationTokens(), false, null);
            }
        }
    }

    /** 一步的累积结果（M4：含缓存命中与中断标志） */
    private record StepOutcome(
            String text,
            String thinking,
            String signature,
            List<ToolCall> toolCalls,
            String stopReason,
            int inputTokens,
            int outputTokens,
            int cacheReadTokens,
            int cacheCreationTokens,
            boolean interrupted,
            String errorMessage) {

        /** 兼容旧 8 参构造（cache=0、interrupted=false） */
        StepOutcome(String text, String thinking, String signature, List<ToolCall> toolCalls,
                    String stopReason, int inputTokens, int outputTokens, String errorMessage) {
            this(text, thinking, signature, toolCalls, stopReason, inputTokens, outputTokens,
                    0, 0, false, errorMessage);
        }
    }
}
