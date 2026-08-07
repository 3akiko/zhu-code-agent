package com.zhubao.tool;

import com.zhubao.agent.AgentUi;
import com.zhubao.permission.PermissionChoice;
import com.zhubao.permission.PermissionDecision;
import com.zhubao.permission.PermissionManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 串行工具执行器（M2 唯一实现，spec F1/F3/N2）：
 * 逐个调用：权限判定（只读自动放行；写类/bash 需确认 a/d/s；危险命令强制确认）→
 * 执行或拒绝 → 收集 ToolResult（拒绝=error 结果，回填给模型）。
 * M5 并行扩展点在 {@link ToolExecutor} 接口处。
 */
public final class SerialToolExecutor implements ToolExecutor {

    private final ToolRegistry registry;
    private final PermissionManager permissions;
    private final AgentUi ui;
    private final int previewLines;

    public SerialToolExecutor(ToolRegistry registry, PermissionManager permissions, AgentUi ui, int previewLines) {
        this.registry = registry;
        this.permissions = permissions;
        this.ui = ui;
        this.previewLines = previewLines;
    }

    @Override
    public List<ToolResult> execute(List<ToolCall> calls) {
        List<ToolResult> results = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            if (ui != null) {
                ui.onToolCall(call);
            }
            PermissionDecision decision = permissions.decide(call);
            if (decision == PermissionDecision.NEED_CONFIRM) {
                PermissionChoice choice = ui == null ? PermissionChoice.ALLOW : ui.askPermission(call);
                if (choice == PermissionChoice.DENY) {
                    ToolResult denied = ToolResult.error(call, "已拒绝执行（用户选择拒绝）");
                    results.add(denied);
                    if (ui != null) {
                        ui.onToolResult(denied, previewLines);
                    }
                    continue;
                }
                if (choice == PermissionChoice.ALLOW_ALWAYS) {
                    permissions.rememberAlways(call);
                }
            } else if (decision == PermissionDecision.DENY) {
                ToolResult denied = ToolResult.error(call, "已拒绝执行（权限禁止）");
                results.add(denied);
                if (ui != null) {
                    ui.onToolResult(denied, previewLines);
                }
                continue;
            }
            Tool tool = registry.byName(call.name()).orElse(null);
            if (tool == null) {
                ToolResult unknown = ToolResult.error(call, "未知工具: " + call.name());
                results.add(unknown);
                if (ui != null) {
                    ui.onToolResult(unknown, previewLines);
                }
                continue;
            }
            ToolResult result = tool.execute(call);
            results.add(result);
            if (ui != null) {
                ui.onToolResult(result, previewLines);
            }
        }
        return results;
    }
}
