package com.zhubao.permission;

/**
 * 权限判定结果（spec F3）。
 */
public enum PermissionDecision {
    /** 放行（只读工具，或命中「总是允许」且非危险） */
    ALLOW,
    /** 拒绝（用户按 d） */
    DENY,
    /** 需要用户确认（写类/bash；危险命令强制确认，即使曾「总是允许」） */
    NEED_CONFIRM
}
