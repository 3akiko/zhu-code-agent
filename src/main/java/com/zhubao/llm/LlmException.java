package com.zhubao.llm;

/**
 * LLM 调用异常（网络、HTTP 状态、JSON 解析等）。
 * 消息面向用户可读，且绝不含 api_key 等敏感信息（N4 安全要求）。
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
