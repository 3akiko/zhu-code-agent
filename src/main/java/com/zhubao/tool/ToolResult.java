package com.zhubao.tool;

/**
 * 一次工具执行结果（回填给模型）。
 *
 * @param id         对应 {@link ToolCall#id()}（协议回填必需）
 * @param name       工具名
 * @param isError    是否执行失败（权限拒绝/超时/业务错误均视为 error，回填给模型）
 * @param output     输出文本（可能带截断标注）
 * @param renderHint 渲染提示（M3，spec F1）：FULL=完整展示（diff）、PREVIEW=按预览行数；默认 PREVIEW
 */
public record ToolResult(String id, String name, boolean isError, String output, RenderHint renderHint) {

    /** 构造成功结果（默认 PREVIEW） */
    public static ToolResult ok(ToolCall call, String output) {
        return new ToolResult(call.id(), call.name(), false, output, RenderHint.PREVIEW);
    }

    /** 构造带渲染提示的成功结果（M3：write/edit 的 diff 用 FULL） */
    public static ToolResult ok(ToolCall call, String output, RenderHint hint) {
        return new ToolResult(call.id(), call.name(), false, output, hint);
    }

    /** 构造失败结果（默认 PREVIEW） */
    public static ToolResult error(ToolCall call, String message) {
        return new ToolResult(call.id(), call.name(), true, message, RenderHint.PREVIEW);
    }

    /** 构造带渲染提示的失败结果 */
    public static ToolResult error(ToolCall call, String message, RenderHint hint) {
        return new ToolResult(call.id(), call.name(), true, message, hint);
    }
}
