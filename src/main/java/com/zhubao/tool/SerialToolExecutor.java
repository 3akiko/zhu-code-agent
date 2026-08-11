package com.zhubao.tool;

import com.zhubao.agent.AgentUi;
import com.zhubao.history.FileHistory;
import com.zhubao.permission.PermissionChoice;
import com.zhubao.permission.PermissionDecision;
import com.zhubao.permission.PermissionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 串行工具执行器（M2 唯一实现，spec F1/F3/N2）：
 * 逐个调用：权限判定（只读自动放行；写类/bash 需确认 a/d/s；危险命令强制确认）→
 * 执行或拒绝 → 收集 ToolResult（拒绝=error 结果，回填给模型）。
 * M3（spec F3）：write_file/edit_file 执行前经 {@link FileHistory} 快照；
 * 执行失败 discardLast、超大文件跳过并在结果注明。history/sessionId 可空（测试兼容）。
 * M5 并行扩展点在 {@link ToolExecutor} 接口处。
 */
public final class SerialToolExecutor implements ToolExecutor {

    private static final String NOTE_OVERSIZE = "（文件过大，未参与快照/回滚）";

    private final ToolRegistry registry;
    private final PermissionManager permissions;
    private final AgentUi ui;
    private final int previewLines;
    private final FileHistory history;
    private final String sessionId;

    public SerialToolExecutor(ToolRegistry registry, PermissionManager permissions, AgentUi ui, int previewLines) {
        this(registry, permissions, ui, previewLines, null, null);
    }

    public SerialToolExecutor(ToolRegistry registry, PermissionManager permissions, AgentUi ui, int previewLines,
                              FileHistory history, String sessionId) {
        this.registry = registry;
        this.permissions = permissions;
        this.ui = ui;
        this.previewLines = previewLines;
        this.history = history;
        this.sessionId = sessionId;
    }

    @Override
    public List<ToolResult> execute(List<ToolCall> calls) {
        List<ToolResult> results = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            // M4（spec F5）：Ctrl+C 中断后停止后续工具调用（当前正在执行的由 BashTool.cancel 处理）
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            if (ui != null) {
                ui.onToolCall(call);
            }
            PermissionDecision decision = permissions.decide(call);
            String denyReason = null;
            if (decision == PermissionDecision.NEED_CONFIRM) {
                PermissionChoice choice = ui == null ? PermissionChoice.ALLOW : ui.askPermission(call);
                if (choice == PermissionChoice.DENY) {
                    denyReason = "已拒绝执行（用户选择拒绝）";
                } else if (choice == PermissionChoice.ALLOW_ALWAYS) {
                    permissions.rememberAlways(call);
                }
            } else if (decision == PermissionDecision.DENY) {
                denyReason = "已拒绝执行（权限禁止）";
            }
            if (denyReason != null) {
                ToolResult denied = ToolResult.error(call, denyReason);
                results.add(denied);
                if (ui != null) {
                    ui.onToolResult(denied, previewLines);
                }
                continue;
            }

            // M3 快照钩子：write/edit 权限通过后、执行前快照（只读/bash 不触发）
            String pathArg = pathOf(call);
            boolean snapshotTaken = false;
            boolean oversize = false;
            if (history != null && sessionId != null && isSnapshotTool(call.name()) && pathArg != null) {
                FileHistory.SnapshotOutcome out = history.snapshotBefore(sessionId, pathArg,
                        call.name() + ": " + pathArg);
                snapshotTaken = out.taken();
                oversize = out.skippedOversize();
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
            if (snapshotTaken && result.isError()) {
                history.discardLast(sessionId);
            }
            if (oversize && !result.isError()) {
                result = ToolResult.ok(call, result.output() + "\n" + NOTE_OVERSIZE, result.renderHint());
            }
            results.add(result);
            if (ui != null) {
                ui.onToolResult(result, previewLines);
            }
        }
        return results;
    }

    private static boolean isSnapshotTool(String name) {
        return "write_file".equals(name) || "edit_file".equals(name);
    }

    private static String pathOf(ToolCall call) {
        Object path = call.arguments().get("path");
        return path == null ? null : String.valueOf(path);
    }
}
