package com.zhubao.tool;

import com.zhubao.agent.AgentUi;
import com.zhubao.history.FileHistory;
import com.zhubao.permission.PermissionChoice;
import com.zhubao.permission.PermissionDecision;
import com.zhubao.permission.PermissionManager;

/**
 * 工具执行器的公共逻辑（M5，spec F2）：单个调用的完整处理——
 * 中断检查由调用方循环负责；这里负责「UI 回调 → 权限判定 → 快照 → 执行 → 结果收集」。
 *
 * <p>M2 的 {@link SerialToolExecutor} 是唯一实现（逐调用串行）；
 * M5 新增 {@link ParallelToolExecutor}（读+task 段并行 / 写·bash 串行 / 保序），
 * 两者共享本类的 {@link #executeOne(ToolCall)}，保证权限/快照/结果语义一致、行为不回归。
 */
public abstract class AbstractToolExecutor implements ToolExecutor {

    protected static final String NOTE_OVERSIZE = "（文件过大，未参与快照/回滚）";

    protected final ToolRegistry registry;
    protected final PermissionManager permissions;
    protected final AgentUi ui;
    protected final int previewLines;
    protected final FileHistory history;
    protected final String sessionId;

    protected AbstractToolExecutor(ToolRegistry registry, PermissionManager permissions, AgentUi ui, int previewLines) {
        this(registry, permissions, ui, previewLines, null, null);
    }

    protected AbstractToolExecutor(ToolRegistry registry, PermissionManager permissions, AgentUi ui, int previewLines,
                                   FileHistory history, String sessionId) {
        this.registry = registry;
        this.permissions = permissions;
        this.ui = ui;
        this.previewLines = previewLines;
        this.history = history;
        this.sessionId = sessionId;
    }

    /**
     * 执行单个工具调用（M2 串行循环体内的完整逻辑迁移）：
     * UI 回调 → 权限判定（只读自动放行；写类/bash 需确认 a/d/s；危险命令强制确认）→
     * M3 快照钩子（write/edit 执行前快照）→ 执行 → 失败 discardLast / 超大标注 → 结果回填。
     * 拒绝=error 结果（回填给模型），不抛异常。
     *
     * <p>M5（spec F2）：并行段内 {@code notifyUi=false}——UI 回调由调度线程在提交前/收集时
     * 串行调用，避免虚拟线程并发写终端；串行执行器保持 {@code notifyUi=true}（行为不变）。
     */
    protected ToolResult executeOne(ToolCall call) {
        return executeOne(call, true);
    }

    protected ToolResult executeOne(ToolCall call, boolean notifyUi) {
        if (notifyUi && ui != null) {
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
            if (notifyUi && ui != null) {
                ui.onToolResult(denied, previewLines);
            }
            return denied;
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
            if (notifyUi && ui != null) {
                ui.onToolResult(unknown, previewLines);
            }
            return unknown;
        }
        ToolResult result = tool.execute(call);
        if (snapshotTaken && result.isError()) {
            history.discardLast(sessionId);
        }
        if (oversize && !result.isError()) {
            result = ToolResult.ok(call, result.output() + "\n" + NOTE_OVERSIZE, result.renderHint());
        }
        if (notifyUi && ui != null) {
            ui.onToolResult(result, previewLines);
        }
        return result;
    }

    protected static boolean isSnapshotTool(String name) {
        return "write_file".equals(name) || "edit_file".equals(name);
    }

    protected static String pathOf(ToolCall call) {
        Object path = call.arguments().get("path");
        return path == null ? null : String.valueOf(path);
    }
}
