package com.zhubao.agent;

import com.zhubao.llm.StreamEvent;
import com.zhubao.permission.PermissionChoice;
import com.zhubao.tool.ToolCall;
import com.zhubao.tool.ToolResult;

/**
 * Agent 循环与 TUI 的解耦回调（spec F10）：
 * 状态行、增量渲染、工具摘要/结果预览、权限确认都由 UI 实现；测试注入自动应答桩。
 */
public interface AgentUi {

    /** 每个 agent 步骤开始（打印状态行，如 ⏳ 正在思考…） */
    void onStep(String status);

    /** 增量事件渲染（TextDelta/ThinkingDelta 等；首个内容到达时由实现负责清除状态行） */
    void onEvent(StreamEvent event);

    /** 工具调用摘要（一行） */
    void onToolCall(ToolCall call);

    /** 工具结果预览（previewLines 行 + 截断标注） */
    void onToolResult(ToolResult result, int previewLines);

    /** 权限确认：行内单键 a/d/s，返回用户选择 */
    PermissionChoice askPermission(ToolCall call);

    /**
     * M5（spec F3.9）：子任务开始（折叠单行状态）。default 空实现——测试桩零改动。
     *
     * @param id            子任务序号（#1、#2 …）
     * @param promptPreview 任务描述首行（简要展示）
     */
    default void onSubtaskStart(int id, String promptPreview) {
    }

    /** M5（spec F3.9）：子任务结束（折叠单行状态 + 摘要首行）。default 空实现。 */
    default void onSubtaskEnd(int id, String summary) {
    }

    /**
     * M5（spec F3.4）：子任务内权限确认的来源标注钩子——主 UI 在弹窗前打印
     * 「[子任务#N] 权限确认: …」，让用户知道是谁在请求。default 空实现（测试桩零改动）。
     */
    default void onSubtaskPermission(int id, ToolCall call) {
    }
}
