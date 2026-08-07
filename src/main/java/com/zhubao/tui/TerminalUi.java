package com.zhubao.tui;

import org.jline.reader.EndOfFileException;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;

/**
 * 终端交互封装：
 * <ul>
 *   <li>JLine3 —— 行编辑 + 本会话输入历史（spec F2）</li>
 *   <li>{@link Ansi} —— ANSI 256 色渲染（方案 B 彩色 TUI）</li>
 * </ul>
 */
public class TerminalUi implements AutoCloseable {

    private final org.jline.terminal.Terminal jlineTerminal;
    private final LineReader lineReader;

    public TerminalUi() {
        try {
            this.jlineTerminal = TerminalBuilder.builder().system(true).build();
        } catch (IOException e) {
            throw new TuiException("终端初始化失败: " + e.getMessage(), e);
        }
        History history = new DefaultHistory();
        this.lineReader = LineReaderBuilder.builder()
                .terminal(jlineTerminal)
                .history(history)
                .option(LineReader.Option.HISTORY_BEEP, false)
                .build();
    }

    /**
     * 读取一行输入（支持方向键编辑与上下翻历史）。
     * 返回 null 表示退出：Ctrl+D（EOF）或 Ctrl+C 中断。
     */
    public String readLine(String prompt) {
        try {
            String line = lineReader.readLine(prompt);
            if (line != null && !line.isEmpty()) {
                lineReader.getHistory().add(line);
            }
            return line;
        } catch (EndOfFileException | UserInterruptException e) {
            return null;
        }
    }

    /** 带颜色打印（不换行）；ansiCode 为 null 时原样输出 */
    public void print(String text, String ansiCode) {
        System.out.print(Ansi.color(text, ansiCode));
        System.out.flush();
    }

    /** 带颜色打印并换行；ansiCode 为 null 时原样输出 */
    public void println(String text, String ansiCode) {
        System.out.println(Ansi.color(text, ansiCode));
        System.out.flush();
    }

    public void println(String text) {
        System.out.println(text);
        System.out.flush();
    }

    /** 打印空行 */
    public void println() {
        System.out.println();
        System.out.flush();
    }

    /**
     * 读取权限确认键（spec F3）：输入 a/d/s 后回车确认。
     * 采用 readLine 而非 raw 单键：raw 单键依赖终端原始模式（tcsetattr），
     * 在部分 PTY/受限终端下不可靠（实测 macOS 受限 PTY 上行缓冲不生效），
     * 回车确认保证跨环境稳定；无输入/异常默认返回 'd'（安全优先：拒绝）。
     */
    public char readSingleKey(String prompt) {
        String line;
        try {
            line = lineReader.readLine(Ansi.color(prompt, Ansi.HIGHLIGHT));
        } catch (org.jline.reader.EndOfFileException | org.jline.reader.UserInterruptException e) {
            return 'd';
        }
        if (line == null || line.isBlank()) {
            return 'd';
        }
        return line.trim().charAt(0);
    }

    /** 清除上一行（光标上移一行 + 清到行尾）。
     * 用于流式开始后移除状态行（不影响其他输出）。 */
    public void clearPreviousLine() {
        System.out.print("\u001b[1A\u001b[K");
        System.out.flush();
    }

    /** 清屏（不清历史，spec F8 /clear）：清屏 + 光标回左上角 */
    public void clearScreen() {
        System.out.print("\u001b[2J\u001b[H");
        System.out.flush();
    }

    org.jline.terminal.Terminal jlineTerminal() {
        return jlineTerminal;
    }

    @Override
    public void close() {
        try {
            jlineTerminal.close();
        } catch (IOException ignored) {
            // 忽略关闭异常
        }
    }
}
