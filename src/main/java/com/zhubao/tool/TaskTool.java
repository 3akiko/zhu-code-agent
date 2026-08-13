package com.zhubao.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhubao.agent.AgentDepth;
import com.zhubao.agent.AgentRunner;
import com.zhubao.agent.AgentUi;
import com.zhubao.agent.SubagentCoordinator;
import com.zhubao.config.AppConfig;
import com.zhubao.conversation.Conversation;
import com.zhubao.history.FileHistory;
import com.zhubao.llm.LlmClient;
import com.zhubao.llm.StreamEvent;
import com.zhubao.llm.ToolSpec;
import com.zhubao.permission.PermissionChoice;
import com.zhubao.permission.PermissionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * `task` 工具（M5，spec F3）：父 agent 在 ReAct 循环中调用即派生一个子任务。
 *
 * <p>子任务 = 独立会话（内存 {@link Conversation}，隔离父历史、不落盘）+ 子 agent 循环
 * （{@link AgentRunner#runSubtask}，SUBTASK_SYSTEM_PROMPT）+ 独立工具池（深度封顶时裁剪
 * task）+ 三层护栏（嵌套深度/并行上限/步数上限）。完成后把结构化摘要作为 tool_result 回填父。
 *
 * <p><b>运行上下文 setter 注入</b>（仿 mewcode AgentTool 模式）：由装配方（ChatApp）在构造后注入
 * clientProvider/permissions/ui/history/sessionId/护栏。深度用 {@link ThreadLocal} 沿
 * 「父→子→孙」执行链传递——子任务 runSubtask 与父工具执行在同一线程同步执行，孙 task 自然看到
 * 深度+1；并行子任务各自虚拟线程互不干扰。
 */
public final class TaskTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 结果摘要截断上限（spec F3.6，常量非配置，YAGNI） */
    public static final int SUMMARY_LIMIT = 2000;

    private Supplier<LlmClient> clientProvider = () -> null;
    private ToolRegistry registry;
    private PermissionManager permissions;
    private AgentUi ui;
    private FileHistory history;
    private String sessionId;
    private int maxDepth = AppConfig.DEFAULT_AGENT_MAX_DEPTH;
    private int maxParallel = AppConfig.DEFAULT_AGENT_MAX_PARALLEL;
    private int maxSteps = AppConfig.DEFAULT_AGENT_MAX_STEPS_PER_SUBTASK;
    private int previewLines = 5;

    /** 在途子任务计数（spec F3.5 并行上限） */
    private final AtomicInteger running = new AtomicInteger();
    /** 子任务序号（#1、#2 …，UI 展示用） */
    private final AtomicInteger nextId = new AtomicInteger();

    // ── 运行上下文注入（装配方调用）────────────────────────
    public void setClientProvider(Supplier<LlmClient> clientProvider) {
        this.clientProvider = clientProvider;
    }

    public void setRegistry(ToolRegistry registry) {
        this.registry = registry;
    }

    public void setPermissions(PermissionManager permissions) {
        this.permissions = permissions;
    }

    public void setUi(AgentUi ui) {
        this.ui = ui;
    }

    public void setHistory(FileHistory history) {
        this.history = history;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public void setMaxParallel(int maxParallel) {
        this.maxParallel = maxParallel;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public void setPreviewLines(int previewLines) {
        this.previewLines = previewLines;
    }

    // ── Tool 接口 ─────────────────────────────────────────

    @Override
    public String name() {
        return "task";
    }

    @Override
    public String description() {
        return "Launch a sub-agent to handle a focused, isolated task. "
                + "The sub-agent runs in its own context (cannot see the current conversation), "
                + "executes tools itself, and returns a concise summary as the result. "
                + "Use it for independent research or implementation subtasks. "
                + "Write a detailed prompt describing what the sub-agent should do.";
    }

    @Override
    public boolean readOnly() {
        // M5（spec F3.4 技术决策）：task 调用本身不是危险操作（不弹权限确认），
        // 危险操作（写/bash）在子任务内部被 PermissionManager 拦截；父已批准的记忆同样作用于子任务。
        return true;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("prompt").put("type", "string").put("description", "子任务任务描述（必填）");
        props.putObject("constraints").put("type", "string").put("description", "可选约束（如必须/禁止事项）");
        schema.putArray("required").add("prompt");
        return schema;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String prompt = promptOf(call);
        if (prompt == null || prompt.isBlank()) {
            return ToolResult.error(call, "task 工具缺少任务描述（prompt 必填）");
        }
        int currentDepth = AgentDepth.get();
        // 运行时兜底拒绝（spec F3.5）：深度已达上限
        if (currentDepth >= maxDepth) {
            return ToolResult.error(call, "嵌套深度超限（最多 " + maxDepth + " 层，父→子→孙封顶）");
        }
        // 并行子任务上限（spec F3.5）
        if (running.incrementAndGet() > maxParallel) {
            running.decrementAndGet();
            return ToolResult.error(call, "并行子任务超限（最多 " + maxParallel + " 个并发）");
        }
        int id = nextId.incrementAndGet();
        int childDepth = currentDepth + 1;
        try {
            return runSubtask(call, prompt, id, childDepth);
        } finally {
            running.decrementAndGet();
        }
    }

    /** 派生子任务并同步等待，返回摘要 ToolResult（spec F3.6） */
    private ToolResult runSubtask(ToolCall call, String prompt, int id, int childDepth) {
        LlmClient client = clientProvider.get();
        if (client == null) {
            return ToolResult.error(call, "task 工具未装配 clientProvider");
        }
        Conversation subConversation = new Conversation();
        subConversation.addUser(prompt);
        SubagentUi subUi = new SubagentUi(ui, id);
        List<ToolSpec> subTools = buildSubtaskTools(childDepth);
        ParallelToolExecutor subExecutor = new ParallelToolExecutor(registry, permissions, subUi,
                previewLines, history, sessionId);

        if (ui != null) {
            ui.onSubtaskStart(id, preview(prompt, 80));
        }
        AgentDepth.set(childDepth);
        try {
            SubagentCoordinator.register(Thread.currentThread());
            AgentRunner.Result result;
            try {
                result = AgentRunner.runSubtask(client, subConversation, subTools, subExecutor, subUi,
                        maxSteps, AgentRunner.STEP_IDLE_TIMEOUT_MS, null);
            } finally {
                SubagentCoordinator.unregister(Thread.currentThread());
            }
            String summary = summarize(id, result);
            if (ui != null) {
                ui.onSubtaskEnd(id, summary);
            }
            boolean isError = result.error() || result.interrupted();
            return isError
                    ? ToolResult.error(call, summary)
                    : ToolResult.ok(call, summary);
        } finally {
            AgentDepth.set(childDepth - 1);
        }
    }

    /**
     * 子任务工具池（spec F3.3/F3.5）：默认与父相同工具集；当 childDepth 已达上限时
     * **不注册 task 工具**（模型不可见，从源头杜绝无效嵌套调用）。
     */
    public List<ToolSpec> buildSubtaskTools(int childDepth) {
        List<ToolSpec> out = new ArrayList<>();
        for (Tool t : registry.all()) {
            if ("task".equals(t.name()) && childDepth >= maxDepth) {
                continue;
            }
            out.add(new ToolSpec(t.name(), t.description(), t.inputSchema()));
        }
        return out;
    }

    /** 结构化摘要（spec F3.6）：状态 + 摘要截断 + token 用量 + 错误原因 */
    private String summarize(int id, AgentRunner.Result r) {
        String status;
        String detail;
        if (r.interrupted()) {
            status = "被中断";
            detail = r.errorMessage() != null ? r.errorMessage() : "生成被中断";
        } else if (r.error()) {
            status = "失败";
            detail = r.errorMessage() != null ? r.errorMessage() : "执行失败";
        } else if (r.limitReached()) {
            status = "失败";
            detail = "达到子任务步数上限（" + maxSteps + "）";
        } else {
            status = "完成";
            detail = preview(r.text(), SUMMARY_LIMIT);
        }
        return "[task#" + id + "] 状态=" + status + " · " + detail
                + " · in " + r.totalInputTokens() + "/out " + r.totalOutputTokens();
    }

    private static String preview(String text, int limit) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        if (oneLine.length() <= limit) {
            return oneLine;
        }
        return oneLine.substring(0, limit) + "…";
    }

    private static String promptOf(ToolCall call) {
        Object prompt = call.arguments().get("prompt");
        return prompt == null ? null : String.valueOf(prompt);
    }

    /** 子任务 UI：全静默（不碰主渲染状态机/终端），权限确认转发主 UI 并标注来源（spec F3.4/T9） */
    private static final class SubagentUi implements AgentUi {

        private final AgentUi parent;
        private final int id;

        SubagentUi(AgentUi parent, int id) {
            this.parent = parent;
            this.id = id;
        }

        @Override public void onStep(String status) { /* 静默 */ }
        @Override public void onEvent(StreamEvent event) { /* 静默 */ }
        @Override public void onToolCall(ToolCall call) { /* 静默 */ }
        @Override public void onToolResult(ToolResult result, int previewLines) { /* 静默 */ }

        @Override
        public PermissionChoice askPermission(ToolCall call) {
            if (parent != null) {
                parent.onSubtaskPermission(id, call);
                return parent.askPermission(call);
            }
            return PermissionChoice.ALLOW;
        }
    }
}
