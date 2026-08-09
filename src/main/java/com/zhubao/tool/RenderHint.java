package com.zhubao.tool;

/**
 * 工具结果渲染提示（M3，spec F1 / plan A+ 决策）：
 * <ul>
 *   <li>{@link #PREVIEW}：按 ui.tool_preview_lines 预览（默认）</li>
 *   <li>{@link #FULL}：完整展示（如 write/edit 结果的 diff，工具层已按 ui.diff_max_lines 截断）</li>
 * </ul>
 * UI 按语义标记渲染，不按工具名分支；本字段仅存在于内存 ToolResult，
 * 落盘走 ContentBlock.ToolResultBlock（不含该字段）→ 会话 JSON 格式不变。
 */
public enum RenderHint {
    PREVIEW,
    FULL
}
