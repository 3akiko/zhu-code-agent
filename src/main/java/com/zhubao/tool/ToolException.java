package com.zhubao.tool;

/**
 * 工具层异常（路径越界、危险命令拒绝等）。
 * 消息面向用户可读；由工具执行层捕获并转为 ToolResult.error 回填给模型。
 */
public class ToolException extends RuntimeException {

    public ToolException(String message) {
        super(message);
    }
}
