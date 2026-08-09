package com.zhubao.permission;

/**
 * 权限模式（M3，spec F4）：
 * <ul>
 *   <li>{@link #NORMAL}：M2 现状——写/bash 逐个确认</li>
 *   <li>{@link #ACCEPT_EDITS}：write_file/edit_file 自动批准；bash 仍按 NORMAL</li>
 *   <li>{@link #BYPASS_PERMISSIONS}：bash 非危险命令也自动批准</li>
 * </ul>
 * 安全红线不削弱：任一模式下危险命令仍强制确认；cwd 外破坏性命令仍立即拒绝；
 * 禁写 .git/ 与 ~/.zhu-code-agent/ 不变。模式仅内存、本次运行内有效、退出重置。
 */
public enum PermissionMode {
    NORMAL,
    ACCEPT_EDITS,
    BYPASS_PERMISSIONS
}
