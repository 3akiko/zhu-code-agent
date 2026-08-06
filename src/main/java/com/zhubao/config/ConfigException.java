package com.zhubao.config;

/**
 * 配置错误（读取/解析/校验失败时抛出）。
 * 消息面向用户可读，且绝不含 api_key 等敏感信息（N4 安全要求）。
 */
public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
