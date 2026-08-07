package com.zhubao.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhubao.llm.LlmException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次工具调用声明（模型输出，已由协议客户端收敛为统一结构）。
 *
 * @param id            工具调用 id（anthropic tool_use_id / openai tool_call_id，协议回填必需）
 * @param name          工具名
 * @param argumentsJson 参数 JSON 字符串（可能未解析，保持原文用于精确记忆与回填）
 */
public record ToolCall(String id, String name, String argumentsJson) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 解析参数为字符串 Map（供工具实现使用）；解析失败返回空 Map（由工具返回可读错误） */
    public Map<String, Object> arguments() {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            JsonNode node = MAPPER.readTree(argumentsJson);
            if (node.isObject()) {
                return MAPPER.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
                });
            }
            return new LinkedHashMap<>();
        } catch (JsonProcessingException e) {
            return new LinkedHashMap<>();
        }
    }

    /** 规范化的参数键（用于「总是允许」精确记忆；bash 按完整命令串记忆） */
    public String canonicalArguments() {
        return argumentsJson == null ? "" : argumentsJson.trim();
    }

    /** 构造一个参数 JSON 字符串（工具实现自测/夹具用） */
    public static String json(Map<String, Object> args) {
        try {
            return MAPPER.writeValueAsString(args);
        } catch (JsonProcessingException e) {
            throw new LlmException("参数序列化失败", e);
        }
    }
}
