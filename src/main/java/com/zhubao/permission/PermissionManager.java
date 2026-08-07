package com.zhubao.permission;

import com.zhubao.tool.DangerGuard;
import com.zhubao.tool.Tool;
import com.zhubao.tool.ToolCall;
import com.zhubao.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 权限判定与「总是允许」记忆（spec F3/F4/F5）：
 * <ul>
 *   <li>只读工具 → ALLOW（自动执行，不询问）</li>
 *   <li>写类/bash：命中「总是允许」（工具+参数精确记忆）且非危险 → ALLOW；危险 → 强制 NEED_CONFIRM</li>
 *   <li>其余 → NEED_CONFIRM</li>
 *   <li>「总是允许」仅内存（本次程序运行内有效），不落盘，退出重置；/permissions 可查看与 reset</li>
 * </ul>
 */
public final class PermissionManager {

    private final ToolRegistry registry;
    /** 记忆键：name|canonicalArguments（bash 按完整命令串；文件写按参数 JSON） */
    private final Set<String> alwaysAllowed = new LinkedHashSet<>();

    public PermissionManager(ToolRegistry registry) {
        this.registry = registry;
    }

    public PermissionDecision decide(ToolCall call) {
        Tool tool = registry.byName(call.name()).orElse(null);
        if (tool == null) {
            // 未知工具由执行层报错；权限上直接放行让执行层处理（避免卡确认）
            return PermissionDecision.ALLOW;
        }
        if (tool.readOnly()) {
            return PermissionDecision.ALLOW;
        }
        String key = keyOf(call);
        if (alwaysAllowed.contains(key)) {
            // 危险命令即使曾「总是允许」也强制确认（spec F4）
            if (isDangerousCall(tool, call)) {
                return PermissionDecision.NEED_CONFIRM;
            }
            return PermissionDecision.ALLOW;
        }
        return PermissionDecision.NEED_CONFIRM;
    }

    /** 记录「总是允许」：工具名 + 规范化参数（仅内存，不落盘） */
    public void rememberAlways(ToolCall call) {
        alwaysAllowed.add(keyOf(call));
    }

    /** /permissions 查看：当前程序运行内的「总是允许」清单 */
    public List<String> allowedList() {
        return List.copyOf(alwaysAllowed);
    }

    /** /permissions reset：清空记忆，之后同类操作重新询问 */
    public void reset() {
        alwaysAllowed.clear();
    }

    private static boolean isDangerousCall(Tool tool, ToolCall call) {
        if (!"bash".equals(tool.name())) {
            return false;
        }
        String command = String.valueOf(call.arguments().getOrDefault("command", ""));
        return DangerGuard.isDangerous(command);
    }

    private static String keyOf(ToolCall call) {
        return call.name() + "|" + call.canonicalArguments();
    }
}
