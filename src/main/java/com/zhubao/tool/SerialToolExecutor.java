package com.zhubao.tool;

import com.zhubao.agent.AgentUi;
import com.zhubao.history.FileHistory;
import com.zhubao.permission.PermissionManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 串行工具执行器（M2 唯一实现，spec F1/F3/N2；M5 改为继承 {@link AbstractToolExecutor}）：
 * 逐个调用 {@link AbstractToolExecutor#executeOne}（权限判定 → 快照 → 执行 → 结果）。
 * M4（spec F5）：Ctrl+C 中断后停止后续工具调用（当前正在执行的由 BashTool.cancel 处理）。
 * history/sessionId 可空（测试兼容）。
 */
public final class SerialToolExecutor extends AbstractToolExecutor {

    public SerialToolExecutor(ToolRegistry registry, PermissionManager permissions, AgentUi ui, int previewLines) {
        super(registry, permissions, ui, previewLines);
    }

    public SerialToolExecutor(ToolRegistry registry, PermissionManager permissions, AgentUi ui, int previewLines,
                              FileHistory history, String sessionId) {
        super(registry, permissions, ui, previewLines, history, sessionId);
    }

    @Override
    public List<ToolResult> execute(List<ToolCall> calls) {
        List<ToolResult> results = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            // M4（spec F5）：Ctrl+C 中断后停止后续工具调用
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            results.add(executeOne(call));
        }
        return results;
    }
}
