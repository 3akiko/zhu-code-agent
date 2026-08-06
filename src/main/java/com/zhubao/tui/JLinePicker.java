package com.zhubao.tui;

import org.jline.terminal.Attributes;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 通用方向键选择列表（spec F1 provider 选择 / F9 会话选择）。
 *
 * <p>↑/↓ 移动光标、Enter 确认、Ctrl+C 取消（返回 empty）。
 * 进入 raw 模式逐键读取，结束时用旧的 Attributes 恢复终端状态。
 */
public class JLinePicker {

    private static final String ANSI_UP = "\u001b[%dA";
    private static final String ANSI_CLEAR_DOWN = "\u001b[J";

    private final org.jline.terminal.Terminal terminal;
    private final TerminalUi ui;

    public JLinePicker(TerminalUi ui) {
        this.terminal = ui.jlineTerminal();
        this.ui = ui;
    }

    public <T> Optional<Integer> pickIndex(String title, List<T> items, Function<T, String> label) {
        if (items.isEmpty()) {
            return Optional.empty();
        }
        int cursor = 0;
        int lines = draw(title, items, label, cursor);
        Attributes previous = terminal.enterRawMode();
        try {
            while (true) {
                int key = terminal.reader().read();
                if (key == 3) { // Ctrl+C 取消
                    return Optional.empty();
                } else if (key == 13 || key == 10) { // Enter 确认
                    return Optional.of(cursor);
                } else if (key == 27) { // ESC 序列（方向键）
                    int c1 = terminal.reader().read();
                    int c2 = terminal.reader().read();
                    if (c1 == '[') {
                        if (c2 == 'A') { // ↑
                            cursor = Math.max(0, cursor - 1);
                        } else if (c2 == 'B') { // ↓
                            cursor = Math.min(items.size() - 1, cursor + 1);
                        }
                    }
                    // 回到列表顶部重绘
                    System.out.print(ANSI_UP.formatted(lines) + ANSI_CLEAR_DOWN);
                    System.out.flush();
                    lines = draw(title, items, label, cursor);
                }
            }
        } catch (IOException e) {
            throw new TuiException("读取按键失败: " + e.getMessage(), e);
        } finally {
            terminal.setAttributes(previous);
        }
    }

    /** 绘制选择列表，返回占用的行数（用于下次重绘定位） */
    private <T> int draw(String title, List<T> items, Function<T, String> label, int cursor) {
        int lines = 0;
        ui.println(title, Ansi.HIGHLIGHT);
        lines++;
        for (int i = 0; i < items.size(); i++) {
            String text = "  " + (i == cursor ? "❯ " : "  ") + label.apply(items.get(i));
            ui.println(text, i == cursor ? Ansi.USER : null);
            lines++;
        }
        ui.println("  ↑/↓ 选择  Enter 确认  Ctrl+C 取消", Ansi.THINKING);
        lines++;
        return lines;
    }
}
