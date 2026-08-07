package com.zhubao.permission;

/**
 * 用户对权限确认的答复（spec F3）。
 */
public enum PermissionChoice {
    /** 允许本次 */
    ALLOW,
    /** 拒绝本次 */
    DENY,
    /** 允许并「总是允许本次」（按工具+参数记忆，程序运行内有效） */
    ALLOW_ALWAYS
}
