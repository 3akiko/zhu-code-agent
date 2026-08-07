package com.zhubao.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhubao.tool.PathGuard;
import com.zhubao.tool.Tool;
import com.zhubao.tool.ToolCall;
import com.zhubao.tool.ToolException;
import com.zhubao.tool.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * edit_file：精确字符串替换（old_string → new_string，spec F2）。
 * 要求唯一匹配：未找到 / 多匹配均返回可读错误，模型调整后重试。
 */
public final class EditFileTool implements Tool {

    private final PathGuard guard;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public EditFileTool(PathGuard guard) {
        this.guard = guard;
    }

    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public String description() {
        return "编辑文件：把文件中唯一匹配的 old_string 精确替换为 new_string；未找到或匹配不唯一会返回错误，需调整后重试。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties")
                .putObject("path").put("type", "string").put("description", "目标文件路径（须在工作区内）")
                .putObject("old_string").put("type", "string").put("description", "要替换的原文（必须唯一匹配）")
                .putObject("new_string").put("type", "string").put("description", "替换后的新文本");
        schema.putArray("required").add("path").add("old_string").add("new_string");
        return schema;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        Map<String, Object> args = call.arguments();
        String path = args.get("path") == null ? null : String.valueOf(args.get("path"));
        String oldString = args.get("old_string") == null ? null : String.valueOf(args.get("old_string"));
        String newString = args.get("new_string") == null ? null : String.valueOf(args.get("new_string"));
        if (path == null || path.isBlank() || oldString == null || oldString.isEmpty() || newString == null) {
            return ToolResult.error(call, "edit_file 缺少参数 path/old_string/new_string");
        }
        try {
            Path target = guard.resolveInWorkspace(path);
            guard.assertNotForbidden(target);
            if (!Files.exists(target) || Files.isDirectory(target)) {
                return ToolResult.error(call, "文件不存在或不是文件: " + path);
            }
            String content = Files.readString(target, StandardCharsets.UTF_8);
            int count = countOccurrences(content, oldString);
            if (count == 0) {
                return ToolResult.error(call, "未找到匹配的旧文本（old_string 在文件中不存在），请调整后重试");
            }
            if (count > 1) {
                return ToolResult.error(call, "旧文本匹配不唯一（共 " + count + " 处），请提供更长/更精确的 old_string");
            }
            String updated = content.replace(oldString, newString);
            Files.writeString(target, updated, StandardCharsets.UTF_8);
            return ToolResult.ok(call, "已替换 1 处 → " + target);
        } catch (ToolException e) {
            return ToolResult.error(call, e.getMessage());
        } catch (IOException e) {
            return ToolResult.error(call, "编辑文件失败: " + ReadFileTool.safe(e));
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
