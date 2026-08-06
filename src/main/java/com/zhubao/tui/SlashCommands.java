package com.zhubao.tui;

/**
 * 斜杠命令（spec F8）：/help /exit /clear。
 * 只负责识别与分类，具体输出与副作用由 ChatApp 执行。
 */
public final class SlashCommands {

    /** 命令处理结果 */
    public enum Action { NONE, HELP, EXIT, CLEAR, NEW }

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
        return switch (line.trim().toLowerCase()) {
            case "/help" -> Action.HELP;
            case "/exit", "/quit" -> Action.EXIT;
            case "/clear" -> Action.CLEAR;
            case "/new" -> Action.NEW;
            default -> Action.NONE;
        };
    }
}
