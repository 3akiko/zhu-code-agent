package com.zhubao.tui;

import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.Display;
import org.jline.utils.InfoCmp;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 通用方向键选择列表（spec F1 provider 选择 / F9 会话选择）。
 *
 * <p>↑/↓ 移动光标、Enter 确认、Ctrl+C 取消（返回 empty）。
 *
 * <p><b>修复（2026-09-10 记录 / 2026-09-18 合入）：重绘改用 JLine 官方 {@link Display}</b>——
 * 原先手写 ANSI 光标序列（{@code \u001b[NA} 上移 + {@code \u001b[J} 清屏）直接写 System.out，
 * 绕过了终端能力判断：在不支持光标控制的终端（DumbTerminal / TERM=dumb / 受限 PTY）下序列失效，
 * 导致每次按键都追加一份完整列表（屏幕重复堆叠）；且行数按"每项一行"硬算，长标题软换行后上移不足。
 * Display 会按终端能力选择重绘策略，并按实际渲染行数（含软换行）维护区域。
 *
 * <p>按键读取改为 {@link BindingReader} + {@link KeyMap}（方向键同时绑定普通/应用模式序列 + terminfo 能力），
 * 不再手写 ESC 序列解析；进入/退出时管理 keypad 模式，保证应用键模式下的方向键可用。
 */
public class JLinePicker {

    /** 列表交互键位语义（不依赖 reader 内部 Binding 枚举） */
    private enum Key { UP, DOWN, ENTER, CANCEL }

    private final Terminal terminal;

    public JLinePicker(TerminalUi ui) {
        this.terminal = ui.jlineTerminal();
    }

    public <T> Optional<Integer> pickIndex(String title, List<T> items, Function<T, String> label) {
        if (items.isEmpty()) {
            return Optional.empty();
        }
        Display display = new Display(terminal, false);
        if (terminal.getHeight() > 0 && terminal.getWidth() > 0) {
            display.resize(terminal.getHeight(), terminal.getWidth());
        }

        int cursor = 0;
        List<AttributedString> lines = render(title, items, label, cursor);
        display.update(lines, 0);

        Attributes previous = terminal.enterRawMode();
        // 应用键盘模式：BindingReader 自定义循环里方向键需要它（对齐 LineReader 的行为）
        terminal.puts(InfoCmp.Capability.keypad_xmit);
        terminal.flush();
        try {
            BindingReader reader = new BindingReader(terminal.reader());
            KeyMap<Key> keyMap = buildKeyMap();
            while (true) {
                Key key = reader.readBinding(keyMap);
                if (key == null || key == Key.CANCEL) {
                    return Optional.empty();
                }
                if (key == Key.ENTER) {
                    return Optional.of(cursor);
                }
                if (key == Key.UP) {
                    cursor = Math.max(0, cursor - 1);
                } else if (key == Key.DOWN) {
                    cursor = Math.min(items.size() - 1, cursor + 1);
                }
                lines = render(title, items, label, cursor);
                display.update(lines, 0);
            }
        } finally {
            // 停止管理该区域（只清内部状态，不擦屏），恢复键盘模式并让光标落到列表下方
            display.reset();
            terminal.puts(InfoCmp.Capability.keypad_local);
            terminal.writer().println();
            terminal.setAttributes(previous);
            terminal.flush();
        }
    }

    /**
     * 键位绑定：方向键同时绑「普通模式（CSI）」与「应用模式（SS3）」两种序列——
     * 终端在 keypad_xmit 之后发的是 {@code \u001bOA/OB}，普通模式是 {@code \u001b[A/B}，
     * 两种都绑才能覆盖不同终端/模式（再叠加 terminfo 能力序列兜底）。
     */
    private KeyMap<Key> buildKeyMap() {
        KeyMap<Key> keyMap = new KeyMap<>();
        keyMap.bind(Key.UP, "\u001b[A", "\u001bOA");
        keyMap.bind(Key.DOWN, "\u001b[B", "\u001bOB");
        String up = KeyMap.key(terminal, InfoCmp.Capability.key_up);
        if (up != null && !up.isEmpty()) {
            keyMap.bind(Key.UP, up);
        }
        String down = KeyMap.key(terminal, InfoCmp.Capability.key_down);
        if (down != null && !down.isEmpty()) {
            keyMap.bind(Key.DOWN, down);
        }
        keyMap.bind(Key.ENTER, "\r", "\n");
        keyMap.bind(Key.CANCEL, "\u0003");
        return keyMap;
    }

    /** 渲染列表为带样式的行（标题高亮 / 当前项用户色 / 提示行灰色） */
    private <T> List<AttributedString> render(String title, List<T> items, Function<T, String> label, int cursor) {
        List<AttributedString> lines = new ArrayList<>(items.size() + 2);
        lines.add(styled(title, Ansi.HIGHLIGHT));
        for (int i = 0; i < items.size(); i++) {
            String text = "  " + (i == cursor ? "❯ " : "  ") + label.apply(items.get(i));
            lines.add(styled(text, i == cursor ? Ansi.USER : null));
        }
        lines.add(styled("  ↑/↓ 选择  Enter 确认  Ctrl+C 取消", Ansi.THINKING));
        return lines;
    }

    /** ANSI 颜色文本 → AttributedString（JLine 负责按显示宽度排版，含中文/emoji 宽字符） */
    private static AttributedString styled(String text, String ansiCode) {
        return AttributedString.fromAnsi(ansiCode == null ? text : Ansi.color(text, ansiCode));
    }
}
