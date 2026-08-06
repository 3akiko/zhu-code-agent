package com.zhubao.tui;

/**
 * TUI 状态机（spec F1）：会话选择 → provider 选择 → 聊天。
 * 有历史会话时先 SESSION_SELECT；新建会话且多 provider 时再 PROVIDER_SELECT。
 */
public enum AppState {
    SESSION_SELECT,
    PROVIDER_SELECT,
    CHAT
}
