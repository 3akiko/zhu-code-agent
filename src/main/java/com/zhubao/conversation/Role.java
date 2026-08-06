package com.zhubao.conversation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 消息角色（枚举，避免魔法字符串）。
 *
 * <p>wire 值保持小写 "user"/"assistant"：JSON 会话文件、Anthropic/OpenAI 请求体
 * 都使用该 wire 值；{@link JsonValue}/{@link JsonCreator} 让 Jackson 序列化/反序列化
 * 时也走 wire 值，会话文件格式保持可读。
 */
public enum Role {

    USER("user"),
    ASSISTANT("assistant");

    private final String wire;

    Role(String wire) {
        this.wire = wire;
    }

    /** 协议/文件中的 wire 值 */
    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static Role fromWire(String value) {
        for (Role role : values()) {
            if (role.wire.equals(value)) {
                return role;
            }
        }
        throw new IllegalArgumentException("未知角色: " + value);
    }
}
