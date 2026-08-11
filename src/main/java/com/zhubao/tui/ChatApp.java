package com.zhubao.tui;

import com.zhubao.agent.AgentRunner;
import com.zhubao.agent.AgentUi;
import com.zhubao.config.AppConfig;
import com.zhubao.context.CompactionOptions;
import com.zhubao.context.CompactionResult;
import com.zhubao.context.ContextCompactor;
import com.zhubao.config.ProviderConfig;
import com.zhubao.history.FileCheckpoint;
import com.zhubao.history.FileHistory;
import com.zhubao.history.RollbackResult;
import com.zhubao.conversation.Conversation;
import com.zhubao.llm.LlmClient;
import com.zhubao.llm.LlmClientFactory;
import com.zhubao.llm.StreamEvent;
import com.zhubao.llm.ToolSpec;
import com.zhubao.permission.PermissionChoice;
import com.zhubao.permission.PermissionManager;
import com.zhubao.permission.PermissionMode;
import com.zhubao.session.ProviderSnapshot;
import com.zhubao.session.Session;
import com.zhubao.session.SessionMeta;
import com.zhubao.session.SessionStore;
import com.zhubao.tool.PathGuard;
import com.zhubao.tool.PlanModeExecutor;
import com.zhubao.tool.RenderHint;
import com.zhubao.tool.SerialToolExecutor;
import com.zhubao.tool.ToolCall;
import com.zhubao.tool.ToolRegistry;
import com.zhubao.tool.ToolResult;
import com.zhubao.tool.builtin.BashTool;

import java.nio.file.Path;
import java.util.function.Supplier;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TUI 状态机与主循环（spec F1/F3/F8/F9 + M2）：
 *
 * <pre>
 * SESSION_SELECT（有历史时：新建对话 + 历史会话列表）
 *   → PROVIDER_SELECT（新建且多 provider 时）
 *   → CHAT（输入 → AgentRunner 循环（含工具执行/权限确认）→ 会话落盘）
 * </pre>
 *
 * 保存时机：每轮回复完成后 + /exit 退出前（spec F9）。
 * M2：工具执行器与权限管理器为程序运行级（「总是允许」不落盘、退出重置，spec F3）。
 */
public class ChatApp {

    private final AppConfig config;
    private final SessionStore sessionStore;
    private final TerminalUi ui;
    private final JLinePicker picker;

    private final ToolRegistry toolRegistry;
    private final PermissionManager permissionManager;
    private final SerialToolExecutor toolExecutor;
    private final List<ToolSpec> toolSpecs;
    private final FileHistory fileHistory;
    // M4：上下文管理（统计/告警/压缩/中断）
    private final ContextCompactor compactor;
    private final BashTool bashTool;
    private long sessionTotalIn;              // 会话累计输入（落盘 SessionMeta）
    private long sessionTotalOut;             // 会话累计输出（落盘 SessionMeta）
    private int lastInputTokens;              // 最近一次请求 inputTokens（占用读数）
    private final TurnInterruptController interrupt; // M4 F5：本轮中断控制（信号/逃生门/状态收敛）
    private ProviderConfig provider;          // 当前 provider（含 api_key，仅内存，不落盘）
    private ProviderSnapshot providerSnapshot; // 会话记录的 provider 快照（不含 api_key）
    private Conversation conversation;
    private SessionMeta sessionMeta;          // 当前会话元数据

    public ChatApp(AppConfig config) {
        this.config = config;
        this.sessionStore = new SessionStore(config.sessionsDir());
        this.ui = new TerminalUi();
        this.picker = new JLinePicker(ui);
        // M2：工具注册表 / 权限管理器为程序运行级（权限不落盘、退出重置）
        // M3：文件历史（检查点快照/回滚）为程序运行级，按会话 id 隔离
        Path workspace = Path.of(System.getProperty("user.dir"));
        PathGuard guard = new PathGuard(workspace);
        this.toolRegistry = new ToolRegistry(guard, config.uiDiffMaxLines());
        this.permissionManager = new PermissionManager(toolRegistry);
        this.toolExecutor = new SerialToolExecutor(toolRegistry, permissionManager, null, config.uiToolPreviewLines());
        this.fileHistory = new FileHistory(defaultSnapshotsRoot(), guard);
        this.toolSpecs = toolRegistry.all().stream()
                .map(t -> new ToolSpec(t.name(), t.description(), t.inputSchema()))
                .toList();
        // M4：压缩器 + 可中断的 bash 工具引用 + 本轮中断控制器（信号/逃生门）
        this.compactor = new ContextCompactor();
        this.bashTool = (BashTool) toolRegistry.byName("bash").orElse(null);
        this.interrupt = new TurnInterruptController(bashTool);
    }

    public void run() {
        try {
            selectSessionOrNew();
            if (provider == null) {
                return; // 用户在启动选择中取消
            }
            chatLoop();
        } finally {
            // M4（spec F5）：退出时优雅关闭在途流线程（cancel + join）
            interrupt.shutdown();
            ui.close();
        }
    }

    // ── SESSION_SELECT：新建对话 或 恢复历史会话 ──────────────────────────
    private void selectSessionOrNew() {
        List<SessionMeta> sessions = sessionStore.list();
        if (sessions.isEmpty()) {
            startNewSession();
            return;
        }
        // 第一项固定为「新建对话」（null 哨兵），其余按时间倒序
        List<SessionMeta> choices = new ArrayList<>();
        choices.add(null);
        choices.addAll(sessions);
        Optional<Integer> choice = picker.pickIndex("选择会话", choices,
                m -> m == null ? "新建对话" : labelOf(m));
        if (choice.isEmpty()) {
            provider = null; // Ctrl+C 取消
            return;
        }
        SessionMeta selected = choices.get(choice.get());
        if (selected == null) {
            startNewSession();
            return;
        }
        restoreSession(selected);
    }

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private String labelOf(SessionMeta m) {
        // 时间转本地时区显示（会话存的是 UTC Instant，列表展示用本地时间）
        String local = m.updatedAt().atZone(ZoneId.systemDefault()).format(TIME_FMT);
        return m.title() + "　" + local + "（" + m.messageCount() + " 条 · " + m.provider().name() + "）";
    }

    private void restoreSession(SessionMeta meta) {
        Optional<Session> loaded = sessionStore.load(meta.id());
        if (loaded.isEmpty()) {
            ui.println("⚠ 会话恢复失败，已新建会话", Ansi.ERROR);
            startNewSession();
            return;
        }
        Session session = loaded.get();
        this.sessionMeta = session.getMeta();
        this.conversation = new Conversation(session.getMessages());
        this.providerSnapshot = session.getMeta().provider();
        // M4（spec F1）：恢复会话累计；占用读数由下一次请求刷新
        this.sessionTotalIn = session.getMeta().totalInputTokens();
        this.sessionTotalOut = session.getMeta().totalOutputTokens();
        this.lastInputTokens = 0;
        this.provider = findProviderForSnapshot(providerSnapshot);
        if (provider == null) {
            ui.println("⚠ 会话记录的 provider 不在当前配置中，已新建会话", Ansi.ERROR);
            startNewSession();
            return;
        }
        ui.println("✔ 已恢复会话「" + sessionMeta.title() + "」（" + conversation.messageCount() + " 条消息）", Ansi.STATUS);
    }

    private void startNewSession() {
        ProviderConfig selected = selectProvider();
        if (selected == null) {
            provider = null;
            return;
        }
        this.provider = selected;
        this.providerSnapshot = toSnapshot(selected);
        this.conversation = new Conversation();
        Instant now = Instant.now();
        this.sessionMeta = new SessionMeta(SessionMeta.newId(), now, now, "新对话", 0, providerSnapshot);
        this.sessionTotalIn = 0;
        this.sessionTotalOut = 0;
        this.lastInputTokens = 0;
    }

    // ── PROVIDER_SELECT：多 provider 时选择，单 provider 直进 ─────────────
    private ProviderConfig selectProvider() {
        List<ProviderConfig> providers = config.providers();
        if (providers.size() == 1) {
            return providers.get(0);
        }
        Optional<Integer> choice = picker.pickIndex("选择 Provider", providers,
                p -> p.getName() + "（" + p.getProtocol() + " · " + p.getModel() + "）");
        return choice.map(providers::get).orElse(null);
    }

    private ProviderConfig findProviderForSnapshot(ProviderSnapshot snap) {
        for (ProviderConfig p : config.providers()) {
            if (p.getName().equals(snap.name())) {
                return p;
            }
        }
        return null;
    }

    private ProviderSnapshot toSnapshot(ProviderConfig p) {
        return new ProviderSnapshot(p.getName(), p.getProtocol(), p.getModel(), p.getBaseUrl());
    }

    // ── CHAT 主循环 ─────────────────────────────────────────────────────
    private void chatLoop() {
        printBanner();
        while (true) {
            String line = ui.readLine(Ansi.color("> ", Ansi.USER));
            if (line == null) { // Ctrl+D 退出
                saveSession();
                return;
            }
            if (line.isBlank()) {
                continue;
            }
            if (SlashCommands.isCommand(line)) {
                if (!handleCommand(line)) {
                    saveSession();
                    return;
                }
                // M4 F5 逃生门：本轮内二次 Ctrl+C → 优雅退出（消费后清除）
                if (interrupt.consumeExitRequest()) {
                    saveSession();
                    return;
                }
                continue;
            }
            sendAndRender(line);
            saveSession();
            // M4 F5 逃生门：本轮内二次 Ctrl+C → 优雅退出（消费后清除）
            if (interrupt.consumeExitRequest()) {
                saveSession();
                return;
            }
        }
    }

    /** 处理斜杠命令；返回 false 表示退出 */
    private boolean handleCommand(String line) {
        switch (SlashCommands.parse(line)) {
            case EXIT -> {
                return false;
            }
            case CLEAR -> ui.clearScreen();
            case HELP -> printHelp();
            case NEW -> {
                if (!startNewConversation()) {
                    ui.println("已取消新建，继续当前会话", Ansi.THINKING);
                }
            }
            case PERMISSIONS -> handlePermissions(line);
            case PLAN -> handlePlan(line);
            case UNDO -> handleUndo();
            case REWIND -> handleRewind();
            case COMPACT -> handleCompact();
            case NONE -> ui.println("⚠ 未知命令，输入 /help 查看帮助", Ansi.ERROR);
        }
        return true;
    }

    /** /permissions：查看/切换权限模式 + 查看/重置「总是允许」清单（spec F5 + M3 F4，均仅内存、退出重置） */
    private void handlePermissions(String line) {
        String cmd = line.trim().toLowerCase();
        if (cmd.endsWith("reset")) {
            permissionManager.reset();
            ui.println("✔ 已清空「总是允许」清单", Ansi.STATUS);
            return;
        }
        // cmd 已小写化 → modeArg 为小写，与常量小写比较
        String modeArg = cmd.substring("/permissions".length()).trim();
        if (modeArg.equals("normal") || modeArg.equals("acceptedits") || modeArg.equals("bypasspermissions")) {
            PermissionMode mode = switch (modeArg) {
                case "acceptedits" -> PermissionMode.ACCEPT_EDITS;
                case "bypasspermissions" -> PermissionMode.BYPASS_PERMISSIONS;
                default -> PermissionMode.NORMAL;
            };
            permissionManager.setMode(mode);
            ui.println("✔ 权限模式 → " + modeLabel(mode), Ansi.STATUS);
            return;
        }
        ui.println("权限模式：" + modeLabel(permissionManager.mode()) + "（仅本次运行内有效，不落盘）", Ansi.HIGHLIGHT);
        ui.println("切换：/permissions normal | acceptEdits | bypassPermissions", Ansi.THINKING);
        List<String> list = permissionManager.allowedList();
        if (list.isEmpty()) {
            ui.println("「总是允许」清单为空（本次程序运行内未记忆任何放行）", Ansi.THINKING);
        } else {
            ui.println("「总是允许」清单（本次程序运行内有效，不落盘）：", Ansi.HIGHLIGHT);
            for (String item : list) {
                ui.println("  " + item, Ansi.THINKING);
            }
            ui.println("使用 /permissions reset 清空", Ansi.THINKING);
        }
    }

    private static String modeLabel(PermissionMode m) {
        return switch (m) {
            case NORMAL -> "normal（逐项确认）";
            case ACCEPT_EDITS -> "acceptEdits（文件编辑自动批准）";
            case BYPASS_PERMISSIONS -> "bypassPermissions（自动批准，危险命令仍确认）";
        };
    }

    /** /new：保存当前会话 → 开新会话（多 provider 时重新选择；取消则恢复原会话） */
    private boolean startNewConversation() {
        saveSession();
        ProviderConfig oldProvider = provider;
        ProviderSnapshot oldSnapshot = providerSnapshot;
        Conversation oldConversation = conversation;
        SessionMeta oldMeta = sessionMeta;
        startNewSession();
        if (provider == null) {
            // 用户在选择 provider 时取消：恢复原会话
            provider = oldProvider;
            providerSnapshot = oldSnapshot;
            conversation = oldConversation;
            sessionMeta = oldMeta;
            return false;
        }
        ui.println("✔ 已新建会话（Provider: " + provider.getName() + " · " + provider.getModel() + "）", Ansi.STATUS);
        return true;
    }

    /** 发送用户消息并运行 agent 循环（spec F1/F3/F7/F10 + M4 F1/F2/F3/F5） */
    private void sendAndRender(String userText) {
        ui.println("❯ " + userText, Ansi.USER);
        maybeAutoCompact();

        LlmClient client = LlmClientFactory.create(provider);
        TuiAgentUi agentUi = new TuiAgentUi();
        SerialToolExecutor executor = new SerialToolExecutor(toolRegistry, permissionManager, agentUi,
                config.uiToolPreviewLines(), fileHistory, sessionMeta.id());

        AgentRunner.Result result = runTurn(() -> AgentRunner.run(client, conversation, userText,
                toolSpecs, executor, agentUi, config.toolMaxCallsPerTurn(),
                AgentRunner.STEP_IDLE_TIMEOUT_MS, interrupt::onStreamCreated));

        // 清掉可能残留的步骤状态行
        agentUi.clearLeftoverStatus();
        ui.println();
        // M4 review-P2：先 applyResult 更新占用/累计/中断标记与告警，再 renderResult 展示当前轮真实统计
        applyResult(result);
        renderResult(result);
    }

    /** 渲染一轮结果（sendAndRender 与计划执行共用；M4 F1/F4 展示统计） */
    private void renderResult(AgentRunner.Result result) {
        if (result.limitReached()) {
            ui.println("⚠ 已达本轮工具调用上限（" + config.toolMaxCallsPerTurn()
                    + "），输入『继续』可开启新一轮", Ansi.ERROR);
        } else if (result.error()) {
            ui.println("⚠ " + result.errorMessage(), Ansi.ERROR);
        } else {
            String cache = "";
            if (result.cacheReadTokens() > 0 || result.cacheCreationTokens() > 0) {
                cache = " · cache read " + result.cacheReadTokens()
                        + " / created " + result.cacheCreationTokens();
            }
            int window = provider.effectiveContextWindow();
            int pct = (int) Math.round(100.0 * lastInputTokens / Math.max(1, window));
            ui.println("── 完成（" + result.stopReason()
                    + " · 本轮 in " + result.totalInputTokens() + " / out " + result.totalOutputTokens()
                    + " · 累计 " + (sessionTotalIn + sessionTotalOut)
                    + " · 占用 " + pct + "% / " + window + cache + "）", Ansi.THINKING);
        }
    }

    // ── M4：本轮执行包装（信号处理 / 中断 / 统计）────────────────────

    /** 包一层本轮执行：安装 SIGINT 处理器（含二次 Ctrl+C 逃生门）、结束恢复并 join 在途流（spec F5） */
    private <T> T runTurn(Supplier<T> task) {
        interrupt.beginTurn();
        try {
            return task.get();
        } finally {
            interrupt.endTurn();
        }
    }

    /** M4（spec F1/F2/F5）：更新占用/累计；中断写 assistant「（已中断）」标记；达阈值告警 */
    private void applyResult(AgentRunner.Result result) {
        if (result.inputTokens() > 0) {
            // M4 变更控制（2026-08-11）：占用基数按协议口径——Anthropic input_tokens 不含缓存，
            // 需 + cacheRead 才是真实占用（OpenAI prompt_tokens 已含缓存，occupancyBasis 原样返回）
            lastInputTokens = provider.occupancyBasis(result.inputTokens(), result.cacheReadTokens());
        }
        sessionTotalIn += result.totalInputTokens();
        sessionTotalOut += result.totalOutputTokens();
        if (result.interrupted()) {
            // 变更控制（2026-08-11）：assistant 角色标记，保持 user/assistant 交替，兼容双协议
            conversation.addAssistant("（已中断）", null, null);
        }
        int window = provider.effectiveContextWindow();
        double ratio = window > 0 ? (double) lastInputTokens / window : 0;
        if (ratio >= config.contextAlertThreshold()) {
            ui.println("⚠ 上下文已达 " + (int) Math.round(ratio * 100) + "%（阈值 "
                    + (int) Math.round(config.contextAlertThreshold() * 100) + "%）", Ansi.ERROR);
        }
    }

    /** M4（spec F3）：生成前占用 ≥ 压缩阈值且未熔断 → 自动压缩 */
    private void maybeAutoCompact() {
        if (provider == null || lastInputTokens <= 0 || compactor.isAutoDisabled()) {
            return;
        }
        int window = provider.effectiveContextWindow();
        double ratio = window > 0 ? (double) lastInputTokens / window : 0;
        if (ratio < config.contextCompactThreshold()) {
            return;
        }
        ui.println("⚠ 上下文占用达 " + (int) Math.round(ratio * 100)
                + "%（阈值 " + (int) Math.round(config.contextCompactThreshold() * 100) + "%），自动压缩…", Ansi.ERROR);
        LlmClient client = LlmClientFactory.create(provider);
        CompactionResult r = runTurn(() -> compactor.compact(conversation, client,
                CompactionOptions.from(config), null));
        if (r.isError()) {
            ui.println("⚠ " + r.errorMessage(), Ansi.ERROR);
            return;
        }
        if (r.compacted()) {
            ui.println("📦 已压缩：折叠 " + r.foldedTurns() + " 轮 · 丢弃 " + r.droppedTurns()
                    + " · 截断 " + r.truncatedResults()
                    + "（缓存已重置，占用以下一次请求复核）", Ansi.STATUS);
            lastInputTokens = 0;
            saveSession();
        }
    }

    /** M4（spec F3）：手动 /compact——任意时刻触发，不受熔断限制 */
    private void handleCompact() {
        if (conversation == null || conversation.messageCount() == 0) {
            ui.println("会话为空，无需压缩", Ansi.THINKING);
            return;
        }
        LlmClient client = LlmClientFactory.create(provider);
        CompactionResult r = runTurn(() -> compactor.compact(conversation, client,
                CompactionOptions.from(config), null));
        if (r.isError()) {
            ui.println("⚠ " + r.errorMessage(), Ansi.ERROR);
            return;
        }
        if (!r.compacted()) {
            ui.println("当前上下文无需压缩", Ansi.THINKING);
            return;
        }
        ui.println("📦 已压缩：折叠 " + r.foldedTurns() + " 轮 · 丢弃 " + r.droppedTurns()
                + " · 截断 " + r.truncatedResults(), Ansi.STATUS);
        lastInputTokens = 0;
        saveSession();
    }

    // ── M3：/plan 先计划后执行（spec F2）──────────────────────────────

    /** /plan：单轮计划循环——runPlan → 展示 → y 执行 / d 拒绝 / 意见重新生成 */
    private void handlePlan(String line) {
        String task = line.trim().substring("/plan".length()).trim();
        if (task.isEmpty()) {
            ui.println("请输入任务描述，如 /plan 重构 xxx", Ansi.ERROR);
            return;
        }
        ui.println("📋 计划模式：先产出计划，批准后才执行（只读调研）", Ansi.HIGHLIGHT);
        boolean first = true;
        while (true) {
            LlmClient client = LlmClientFactory.create(provider);
            TuiAgentUi agentUi = new TuiAgentUi();
            PlanModeExecutor planExecutor = new PlanModeExecutor(toolRegistry, permissionManager, agentUi,
                    config.uiToolPreviewLines());
            AgentRunner.Result plan = first
                    ? runTurn(() -> AgentRunner.runPlan(client, conversation, task, toolSpecs, planExecutor, agentUi,
                            config.toolMaxCallsPerTurn(), AgentRunner.STEP_IDLE_TIMEOUT_MS, interrupt::onStreamCreated))
                    : runTurn(() -> AgentRunner.runPlanContinue(client, conversation, toolSpecs, planExecutor, agentUi,
                            config.toolMaxCallsPerTurn(), AgentRunner.STEP_IDLE_TIMEOUT_MS, interrupt::onStreamCreated));
            first = false;
            agentUi.clearLeftoverStatus();
            ui.println();
            applyResult(plan);
            // /plan 改变了会话（任务/调研/计划/意见/执行），各出口统一落盘（review P2-1，对齐 sendAndRender）
            if (plan.error()) {
                ui.println("⚠ " + plan.errorMessage(), Ansi.ERROR);
                saveSession();
                return;
            }
            if (plan.limitReached()) {
                ui.println("⚠ 计划阶段已达工具调用上限，请重试", Ansi.ERROR);
                saveSession();
                return;
            }
            ui.println("── 计划完成，请审批 ──", Ansi.HIGHLIGHT);
            String answer = ui.readLine(Ansi.color("[计划] 批准执行(y) / 拒绝(d) / 输入修改意见重新生成？", Ansi.HIGHLIGHT));
            if (answer == null) {
                ui.println("已取消，计划未执行", Ansi.THINKING);
                saveSession();
                return;
            }
            String a = answer.trim();
            if (a.equalsIgnoreCase("y")) {
                ui.println("✔ 已批准，开始执行…", Ansi.STATUS);
                executePlannedTurn();
                saveSession();
                return;
            }
            if (a.equalsIgnoreCase("d")) {
                ui.println("已拒绝计划，本轮结束（无副作用）", Ansi.THINKING);
                saveSession();
                return;
            }
            conversation.addUser("（对计划的修改意见）" + a);
            ui.println("↻ 已收到修改意见，重新生成计划…", Ansi.THINKING);
        }
    }

    /** 计划批准后的执行阶段：不再 addUser（会话已含任务+计划+调研） */
    private void executePlannedTurn() {
        LlmClient client = LlmClientFactory.create(provider);
        TuiAgentUi agentUi = new TuiAgentUi();
        SerialToolExecutor executor = new SerialToolExecutor(toolRegistry, permissionManager, agentUi,
                config.uiToolPreviewLines(), fileHistory, sessionMeta.id());
        AgentRunner.Result result = runTurn(() -> AgentRunner.runExecution(client, conversation, toolSpecs,
                executor, agentUi, config.toolMaxCallsPerTurn(),
                AgentRunner.STEP_IDLE_TIMEOUT_MS, interrupt::onStreamCreated));
        agentUi.clearLeftoverStatus();
        ui.println();
        applyResult(result);
        renderResult(result);
    }

    // ── M3：/undo /rewind（spec F3，统一检查点机制）────────────────────

    /** /undo：回退最近一个检查点（快捷） */
    private void handleUndo() {
        renderRollback(fileHistory.undo(sessionMeta.id()));
    }

    /** /rewind：列出检查点（最新在上）选择回退 */
    private void handleRewind() {
        List<FileCheckpoint> list = fileHistory.list(sessionMeta.id());
        if (list.isEmpty()) {
            ui.println("没有可回滚的检查点", Ansi.THINKING);
            return;
        }
        List<FileCheckpoint> reversed = new ArrayList<>(list);
        java.util.Collections.reverse(reversed);
        Optional<Integer> pick = picker.pickIndex("选择要回退到的检查点", reversed,
                cp -> cp.timestamp().atZone(ZoneId.systemDefault()).format(TIME_FMT) + "  " + cp.summary());
        if (pick.isEmpty()) {
            return;
        }
        int index = list.size() - 1 - pick.get();
        renderRollback(fileHistory.rewindTo(sessionMeta.id(), index));
    }

    /** 展示回滚结果并把回滚记录写回会话（spec F3：落盘后恢复可见） */
    private void renderRollback(RollbackResult r) {
        if (!r.ok()) {
            ui.println("⚠ " + r.message(), Ansi.ERROR);
            for (String a : r.actions()) {
                ui.println("  " + a, Ansi.THINKING);
            }
            return;
        }
        ui.println("✔ " + r.message(), Ansi.STATUS);
        for (String a : r.actions()) {
            ui.println("  " + a, Ansi.THINKING);
        }
        String record = "[回滚] " + r.message();
        if (!r.actions().isEmpty()) {
            record += "（" + String.join("；", r.actions()) + "）";
        }
        conversation.addUser(record);
        saveSession();
    }

    private static Path defaultSnapshotsRoot() {
        return Path.of(System.getProperty("user.home"), ".zhu-code-agent", "snapshots");
    }

    /** Agent 循环的 TUI 回调（spec F10：每步状态行/工具摘要/结果预览/行内权限确认） */
    private final class TuiAgentUi implements AgentUi {

        private boolean stepStatusCleared;
        /** 当前行以思考灰字开头、尚未换行：遇到正文/工具摘要/结果预览时先换行（思考与正文分行） */
        private boolean thinkingOpen;

        @Override
        public void onStep(String status) {
            interrupt.onStep();
            stepStatusCleared = false;
            thinkingOpen = false;
            ui.println(status, Ansi.THINKING);
        }

        @Override
        public void onEvent(StreamEvent event) {
            if (event instanceof StreamEvent.TextDelta td) {
                clearStepStatus();
                breakThinkingLine();
                ui.print(td.text(), null);
            } else if (event instanceof StreamEvent.ThinkingDelta td) {
                clearStepStatus();
                ui.print(td.text(), Ansi.THINKING);
                thinkingOpen = true;
            }
        }

        @Override
        public void onToolCall(ToolCall call) {
            interrupt.onToolCall();
            clearStepStatus();
            breakThinkingLine();
            ui.println("🔧 " + toolSummary(call), Ansi.HIGHLIGHT);
        }

        @Override
        public void onToolResult(ToolResult result, int previewLines) {
            breakThinkingLine();
            if (result.renderHint() == RenderHint.FULL) {
                // M3 spec F1：diff 完整彩色展示（+ 绿 / - 红 / @@ 亮青）
                ui.println("└ " + result.name() + " → " + coloredDiff(result.output()), null);
            } else {
                ui.println(resultPreview(result, previewLines), Ansi.THINKING);
            }
        }

        /** diff 行彩色渲染（spec F1）：行首 + → 绿（STATUS）、- → 红（ERROR）、@@ → 亮青（HIGHLIGHT） */
        private String coloredDiff(String text) {
            if (text == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) {
                    sb.append('\n');
                }
                String l = lines[i];
                if (l.startsWith("+")) {
                    sb.append(Ansi.color(l, Ansi.STATUS));
                } else if (l.startsWith("-")) {
                    sb.append(Ansi.color(l, Ansi.ERROR));
                } else if (l.startsWith("@@")) {
                    sb.append(Ansi.color(l, Ansi.HIGHLIGHT));
                } else {
                    sb.append(l);
                }
            }
            return sb.toString();
        }

        /** 若当前行还是思考灰字，先换行再输出其他内容 */
        private void breakThinkingLine() {
            if (thinkingOpen) {
                ui.println();
                thinkingOpen = false;
            }
        }

        @Override
        public PermissionChoice askPermission(ToolCall call) {
            String prompt = "[权限] " + toolSummary(call) + " → 允许(a) / 拒绝(d) / 总是允许本次(s)？(输入后回车)";
            while (true) {
                char c = ui.readSingleKey(prompt + " ");
                switch (Character.toLowerCase(c)) {
                    case 'a' -> {
                        return PermissionChoice.ALLOW;
                    }
                    case 'd' -> {
                        return PermissionChoice.DENY;
                    }
                    case 's' -> {
                        return PermissionChoice.ALLOW_ALWAYS;
                    }
                    default -> ui.println("（请按 a/d/s）", Ansi.THINKING);
                }
            }
        }

        void clearLeftoverStatus() {
            if (!stepStatusCleared) {
                ui.clearPreviousLine();
                stepStatusCleared = true;
            }
        }

        private void clearStepStatus() {
            if (!stepStatusCleared) {
                ui.clearPreviousLine();
                stepStatusCleared = true;
            }
        }

        /** 工具调用一行摘要（spec F10） */
        private String toolSummary(ToolCall call) {
            Map<String, Object> args = call.arguments();
            if ("bash".equals(call.name())) {
                return "bash " + args.getOrDefault("command", "");
            }
            for (String key : new String[]{"path", "pattern"}) {
                if (args.containsKey(key)) {
                    return call.name() + " " + args.get(key);
                }
            }
            String json = call.argumentsJson();
            return call.name() + (json == null || json.isBlank() ? "" : " " + truncate(json, 80));
        }

        /** 工具结果预览：前 previewLines 行 + 截断标注（spec F10） */
        private String resultPreview(ToolResult result, int previewLines) {
            String head = result.isError() ? "⚠ " : "└ ";
            return head + result.name() + " → " + firstLines(result.output(), previewLines);
        }

        private String firstLines(String text, int n) {
            if (text == null) {
                return "";
            }
            String[] lines = text.split("\n", -1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(lines.length, Math.max(0, n)); i++) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(lines[i]);
            }
            if (lines.length > n) {
                sb.append("\n…已截断，共 ").append(lines.length).append(" 行");
            }
            return sb.toString();
        }

        private String truncate(String text, int max) {
            return text.length() <= max ? text : text.substring(0, max) + "…";
        }
    }

    /** 保存会话：空会话不落盘；刷新 updatedAt/标题/消息数 */
    private void saveSession() {
        if (conversation == null || conversation.messageCount() == 0) {
            return;
        }
        SessionMeta refreshed = new SessionMeta(
                sessionMeta.id(),
                sessionMeta.createdAt(),
                Instant.now(),
                conversation.previewTitle(),
                conversation.messageCount(),
                providerSnapshot,
                sessionTotalIn,
                sessionTotalOut);
        this.sessionMeta = refreshed;
        sessionStore.save(new Session(refreshed, conversation.getMessages()));
    }

    private void printBanner() {
        ui.println("zhuCodeAgent v0.2.0 —— 命令行 Coding Agent（M2：Agent 循环与 Tool Use）", Ansi.HIGHLIGHT);
        ui.println("Provider: " + provider.getName() + " · " + provider.getProtocol() + " · " + provider.getModel()
                + "　输入 /help 查看帮助", Ansi.STATUS);
    }

    private void printHelp() {
        ui.println("可用命令：", Ansi.HIGHLIGHT);
        ui.println("  /help         显示帮助与当前 provider/model");
        ui.println("  /clear        清屏（保留会话历史）");
        ui.println("  /new          保存当前会话并新建一个会话");
        ui.println("  /permissions  查看「总是允许」清单（/permissions reset 清空）");
        ui.println("  /compact      手动压缩上下文（折叠最旧轮次为摘要）");
        ui.println("  /exit         退出并保存会话");
        ui.println("当前： " + provider.getName() + " · " + provider.getModel());
    }
}
