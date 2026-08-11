package com.zhubao.tui;

/**
 * 斜杠命令（spec F8 + M3 F2/F3 + M4）：/help /exit /clear /new /permissions /plan /undo /rewind /compact。
 * 只负责识别与分类，具体输出与副作用由 ChatApp 执行。
 */
public final class SlashCommands {

    /** 命令处理结果 */
    public enum Action { NONE, HELP, EXIT, CLEAR, NEW, PERMISSIONS, PLAN, UNDO, REWIND, COMPACT }

    private SlashCommands() {
    }

    /** 是否为斜杠命令（/ 开头且长度 > 1，避免把单独 "/" 当命令） */
    public static boolean isCommand(String line) {
        return line != null && line.startsWith("/") && line.length() > 1;
    }

    /** 解析命令，未知命令返回 NONE（由调用方提示 /help） */
    public static Action parse(String line) {
        if (line == null) {
            return Action.NONE;
        }
        String cmd = line.trim().toLowerCase();
        return switch (cmd) {
            case "/help" -> Action.HELP;
            case "/exit", "/quit" -> Action.EXIT;
            case "/clear" -> Action.CLEAR;
            case "/new" -> Action.NEW;
            case "/undo" -> Action.UNDO;
            case "/rewind" -> Action.REWIND;
            case "/compact" -> Action.COMPACT;
            default -> {
                // 带参数命令按前缀识别：/permissions [reset|模式]、/plan <任务>
                if (cmd.equals("/permissions") || cmd.startsWith("/permissions ")) {
                    yield Action.PERMISSIONS;
                }
                if (cmd.equals("/plan") || cmd.startsWith("/plan ")) {
                    yield Action.PLAN;
                }
                yield Action.NONE;
            }
        };
    }
}
