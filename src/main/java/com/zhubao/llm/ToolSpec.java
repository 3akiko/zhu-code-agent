package com.zhubao.llm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具定义（注入请求体，供模型生成工具调用）。
 *
 * @param name        工具名
 * @param description 工具说明
 * @param inputSchema 参数 JSON Schema
 */
public record ToolSpec(String name, String description, JsonNode inputSchema) {
}
