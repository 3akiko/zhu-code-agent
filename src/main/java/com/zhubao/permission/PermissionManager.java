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
 *   <li>M3 权限模式（spec F4）：NORMAL / ACCEPT_EDITS（写文件自动批准）/ BYPASS_PERMISSIONS（bash 非危险自动批准）；模式仅内存</li>
 * </ul>
 */
public final class PermissionManager {

    private final ToolRegistry registry;
    /** 记忆键：name|canonicalArguments（bash 按完整命令串；文件写按参数 JSON） */
    private final Set<String> alwaysAllowed = new LinkedHashSet<>();

    /** 权限模式（M3，spec F4）：默认 NORMAL；仅内存、退出重置；/permissions 可切换 */
    private PermissionMode mode = PermissionMode.NORMAL;

    public PermissionManager(ToolRegistry registry) {
        this.registry = registry;
    }

    /** 当前权限模式（M3，spec F4） */
    public PermissionMode mode() {
        return mode;
    }

    /** 切换权限模式；null 回退 NORMAL */
    public void setMode(PermissionMode mode) {
        this.mode = mode != null ? mode : PermissionMode.NORMAL;
    }

    private static boolean isWriteEdit(String name) {
        return "write_file".equals(name) || "edit_file".equals(name);
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
        boolean writeEdit = isWriteEdit(call.name());
        boolean bash = "bash".equals(call.name());
        // M3 权限模式（spec F4）：acceptEdits / bypassPermissions 自动批准写文件；bypass 还自动批准 bash 非危险命令
        if (writeEdit && (mode == PermissionMode.ACCEPT_EDITS || mode == PermissionMode.BYPASS_PERMISSIONS)) {
            return PermissionDecision.ALLOW;
        }
        if (bash && mode == PermissionMode.BYPASS_PERMISSIONS && !isDangerousCall(tool, call)) {
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

    /**
     * 「总是允许」记忆键（spec F3：文件写按路径、bash 按完整命令串精确记忆）：
     * <ul>
     *   <li>write_file / edit_file → 按目标路径（同路径不同内容不再询问）</li>
     *   <li>bash → 按完整命令串（只放行完全相同的命令）</li>
     * </ul>
     */
    private static String keyOf(ToolCall call) {
        if ("write_file".equals(call.name()) || "edit_file".equals(call.name())) {
            Object path = call.arguments().get("path");
            return call.name() + "|path:" + (path == null ? "" : String.valueOf(path).trim());
        }
        return call.name() + "|" + call.canonicalArguments();
    }
}
