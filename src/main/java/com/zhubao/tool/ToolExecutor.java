package com.zhubao.tool;

import java.util.List;

/**
 * 工具执行器接口（spec N2 扩展点）：
 * 消息循环只依赖 {@code execute(List<ToolCall>) → List<ToolResult>}，
 * M2 仅实现串行执行；M5 并行工具执行 = 新增实现（如读类并行、写类串行），
 * 循环/回填/落盘/权限代码无需改动。
 */
public interface ToolExecutor {

    /** 执行一批工具调用，返回与调用一一对应的结果列表（顺序一致） */
    List<ToolResult> execute(List<ToolCall> calls);
}
