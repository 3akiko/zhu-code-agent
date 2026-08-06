package com.zhubao.tui;

import com.zhubao.config.AppConfig;
import com.zhubao.config.ProviderConfig;
import com.zhubao.conversation.Conversation;
import com.zhubao.llm.LlmClient;
import com.zhubao.llm.LlmClientFactory;
import com.zhubao.llm.StreamEvent;
import com.zhubao.session.ProviderSnapshot;
import com.zhubao.session.Session;
import com.zhubao.session.SessionMeta;
import com.zhubao.session.SessionStore;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * TUI 状态机与主循环（spec F1/F3/F8/F9）：
 *
 * <pre>
 * SESSION_SELECT（有历史时：新建对话 + 历史会话列表）
 *   → PROVIDER_SELECT（新建且多 provider 时）
 *   → CHAT（输入 → TurnRunner 流式渲染 → 会话落盘）
 * </pre>
 *
 * 保存时机：每轮回复完成后 + /exit 退出前（spec F9）。
 */
public class ChatApp {

    private final AppConfig config;
    private final SessionStore sessionStore;
    private final TerminalUi ui;
    private final JLinePicker picker;

    private ProviderConfig provider;          // 当前 provider（含 api_key，仅内存，不落盘）
    private ProviderSnapshot providerSnapshot; // 会话记录的 provider 快照（不含 api_key）
    private Conversation conversation;
    private SessionMeta sessionMeta;          // 当前会话元数据

    public ChatApp(AppConfig config) {
        this.config = config;
        this.sessionStore = new SessionStore(config.sessionsDir());
        this.ui = new TerminalUi();
        this.picker = new JLinePicker(ui);
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
            case NONE -> ui.println("⚠ 未知命令，输入 /help 查看帮助", Ansi.ERROR);
        }
        return true;
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

    /** 发送用户消息并流式渲染回复（spec F3/F7） */
    private void sendAndRender(String userText) {
        ui.println("❯ " + userText, Ansi.USER);
        ui.println("⏳ 正在生成…", Ansi.THINKING);

        LlmClient client = LlmClientFactory.create(provider);
        TurnRunner.Result result = TurnRunner.run(client, conversation, userText, event -> {
            if (event instanceof StreamEvent.TextDelta td) {
                ui.print(td.text(), null);           // 正文：正常颜色，到达即显示
            } else if (event instanceof StreamEvent.ThinkingDelta td) {
                ui.print(td.text(), Ansi.THINKING);  // 思考：灰色小字
            }
        });

        ui.println();
        if (result.error()) {
            ui.println("⚠ " + result.errorMessage(), Ansi.ERROR);
        } else {
            conversation.addAssistant(result.text(), result.thinking(), result.signature());
            ui.println("── 完成（" + result.stopReason()
                    + " · in " + result.inputTokens() + " / out " + result.outputTokens() + " tokens）", Ansi.THINKING);
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
        ui.println("zhuCodeAgent v0.1.0 —— 命令行 Coding Agent", Ansi.HIGHLIGHT);
        ui.println("Provider: " + provider.getName() + " · " + provider.getProtocol() + " · " + provider.getModel()
                + "　输入 /help 查看帮助", Ansi.STATUS);
    }

    private void printHelp() {
        ui.println("可用命令：", Ansi.HIGHLIGHT);
        ui.println("  /help  显示帮助与当前 provider/model");
        ui.println("  /clear 清屏（保留会话历史）");
        ui.println("  /new   保存当前会话并新建一个会话");
        ui.println("  /exit  退出并保存会话");
        ui.println("当前： " + provider.getName() + " · " + provider.getModel());
    }
}
