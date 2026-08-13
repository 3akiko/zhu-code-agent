package com.zhubao.tool;

import com.zhubao.agent.AgentUi;
import com.zhubao.permission.PermissionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 计划阶段执行器（M3，spec F2）：/plan 只读调研期间使用。
 * write_file / edit_file / bash 一律拦截并回填可读错误（无副作用、无快照）；
 * 其余（read_file/grep/glob）委托内部 {@link SerialToolExecutor} 正常执行（只读自动放行）。
 */
public final class PlanModeExecutor implements ToolExecutor {

    // M5 review 修复（2026-08-13）：/plan 计划阶段必须拦截 task——否则可经子任务绕过只读约束产生副作用
    private static final Set<String> WRITE_TOOLS = Set.of("write_file", "edit_file", "bash", "task");

    private final SerialToolExecutor delegate;
    private final AgentUi ui;
    private final int previewLines;

    public PlanModeExecutor(ToolRegistry registry, PermissionManager permissions, AgentUi ui, int previewLines) {
        this.ui = ui;
        this.previewLines = previewLines;
        this.delegate = new SerialToolExecutor(registry, permissions, ui, previewLines);
    }

    @Override
    public List<ToolResult> execute(List<ToolCall> calls) {
        List<ToolResult> results = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            if (WRITE_TOOLS.contains(call.name())) {
                if (ui != null) {
                    ui.onToolCall(call);
                }
                ToolResult blocked = ToolResult.error(call,
                        "计划阶段禁止该操作（write_file/edit_file/bash/task 不可用，仅只读调研）");
                results.add(blocked);
                if (ui != null) {
                    ui.onToolResult(blocked, previewLines);
                }
            } else {
                results.addAll(delegate.execute(List.of(call)));
            }
        }
        return results;
    }
}
