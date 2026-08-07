package com.zhubao.tui;

import com.zhubao.agent.AgentRunner;
import com.zhubao.agent.AgentUi;
import com.zhubao.config.AppConfig;
import com.zhubao.config.ProviderConfig;
import com.zhubao.conversation.Conversation;
import com.zhubao.llm.LlmClient;
import com.zhubao.llm.LlmClientFactory;
import com.zhubao.llm.StreamEvent;
import com.zhubao.llm.ToolSpec;
import com.zhubao.permission.PermissionChoice;
import com.zhubao.permission.PermissionManager;
import com.zhubao.session.ProviderSnapshot;
import com.zhubao.session.Session;
import com.zhubao.session.SessionMeta;
import com.zhubao.session.SessionStore;
import com.zhubao.tool.PathGuard;
import com.zhubao.tool.SerialToolExecutor;
import com.zhubao.tool.ToolCall;
import com.zhubao.tool.ToolRegistry;
import com.zhubao.tool.ToolResult;

import java.nio.file.Path;
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
        Path workspace = Path.of(System.getProperty("user.dir"));
        this.toolRegistry = new ToolRegistry(new PathGuard(workspace));
        this.permissionManager = new PermissionManager(toolRegistry);
        this.toolExecutor = new SerialToolExecutor(toolRegistry, permissionManager, null, config.uiToolPreviewLines());
        this.toolSpecs = toolRegistry.all().stream()
                .map(t -> new ToolSpec(t.name(), t.description(), t.inputSchema()))
                .toList();
    }

    public void run() {
        try {
            selectSessionOrNew();
            if (provider == null) {
                return; // 用户在启动选择中取消
            }
            chatLoop();
        } finally {
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
                continue;
            }
            sendAndRender(line);
            saveSession();
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
            case NONE -> ui.println("⚠ 未知命令，输入 /help 查看帮助", Ansi.ERROR);
        }
        return true;
    }

    /** /permissions：查看/重置「总是允许」清单（spec F5，仅内存、退出重置） */
    private void handlePermissions(String line) {
        if (line.trim().toLowerCase().endsWith("reset")) {
            permissionManager.reset();
            ui.println("✔ 已清空「总是允许」清单", Ansi.STATUS);
            return;
        }
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

    /** 发送用户消息并运行 agent 循环（spec F1/F3/F7/F10） */
    private void sendAndRender(String userText) {
        ui.println("❯ " + userText, Ansi.USER);

        LlmClient client = LlmClientFactory.create(provider);
        TuiAgentUi agentUi = new TuiAgentUi();
        SerialToolExecutor executor = new SerialToolExecutor(toolRegistry, permissionManager, agentUi,
                config.uiToolPreviewLines());

        AgentRunner.Result result = AgentRunner.run(client, conversation, userText,
                toolSpecs, executor, agentUi, config.toolMaxCallsPerTurn());

        // 清掉可能残留的步骤状态行
        agentUi.clearLeftoverStatus();
        ui.println();

        if (result.limitReached()) {
            ui.println("⚠ 已达本轮工具调用上限（" + config.toolMaxCallsPerTurn()
                    + "），输入『继续』可开启新一轮", Ansi.ERROR);
        } else if (result.error()) {
            ui.println("⚠ " + result.errorMessage(), Ansi.ERROR);
        } else {
            ui.println("── 完成（" + result.stopReason()
                    + " · in " + result.inputTokens() + " / out " + result.outputTokens() + " tokens）", Ansi.THINKING);
        }
    }

    /** Agent 循环的 TUI 回调（spec F10：每步状态行/工具摘要/结果预览/行内权限确认） */
    private final class TuiAgentUi implements AgentUi {

        private boolean stepStatusCleared;

        @Override
        public void onStep(String status) {
            stepStatusCleared = false;
            ui.println(status, Ansi.THINKING);
        }

        @Override
        public void onEvent(StreamEvent event) {
            if (event instanceof StreamEvent.TextDelta td) {
                clearStepStatus();
                ui.print(td.text(), null);
            } else if (event instanceof StreamEvent.ThinkingDelta td) {
                clearStepStatus();
                ui.print(td.text(), Ansi.THINKING);
            }
        }

        @Override
        public void onToolCall(ToolCall call) {
            clearStepStatus();
            ui.println("🔧 " + toolSummary(call), Ansi.HIGHLIGHT);
        }

        @Override
        public void onToolResult(ToolResult result, int previewLines) {
            ui.println(resultPreview(result, previewLines), Ansi.THINKING);
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
                providerSnapshot);
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
        ui.println("  /exit         退出并保存会话");
        ui.println("当前： " + provider.getName() + " · " + provider.getModel());
    }
}
