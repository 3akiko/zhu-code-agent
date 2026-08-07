package com.zhubao.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 内置工具抽象（spec F2）。
 *
 * <p>name/description/inputSchema 供请求体 tools 定义与模型调用；
 * execute 接收解析后的 {@link ToolCall}，返回 {@link ToolResult}。
 * 所有文件路径类工具必须经 {@link PathGuard} 校验后再操作。
 */
public interface Tool {

    /** 工具名（模型调用时的函数名） */
    String name();

    /** 工具说明（注入请求体 tools 定义） */
    String description();

    /** 参数 JSON Schema（输入结构，供模型生成参数） */
    JsonNode inputSchema();

    /** 是否只读（只读工具自动放行，不需权限确认，spec F3） */
    default boolean readOnly() {
        return false;
    }

    /** 执行工具调用；参数解析失败/业务错误均返回 isError=true 的 ToolResult（不抛异常） */
    ToolResult execute(ToolCall call);
}
