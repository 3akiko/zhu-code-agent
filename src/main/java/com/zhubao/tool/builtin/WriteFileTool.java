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
 * write_file：创建或覆写文件（spec F2）。先过 PathGuard（含禁写 .git/ 与 ~/.zhu-code-agent/）。
 */
public final class WriteFileTool implements Tool {

    private final PathGuard guard;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public WriteFileTool(PathGuard guard) {
        this.guard = guard;
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "创建新文件或覆写已有文件（仅工作区内；危险目录 .git 与程序自身目录禁止）。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties")
                .putObject("path").put("type", "string").put("description", "目标文件路径（须在工作区内）")
                .putObject("content").put("type", "string").put("description", "要写入的完整内容");
        schema.putArray("required").add("path").add("content");
        return schema;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        Map<String, Object> args = call.arguments();
        String path = args.get("path") == null ? null : String.valueOf(args.get("path"));
        String content = args.get("content") == null ? null : String.valueOf(args.get("content"));
        if (path == null || path.isBlank()) {
            return ToolResult.error(call, "write_file 缺少参数 path");
        }
        if (content == null) {
            return ToolResult.error(call, "write_file 缺少参数 content");
        }
        try {
            Path target = guard.resolveInWorkspace(path);
            guard.assertNotForbidden(target);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            Files.write(target, bytes);
            return ToolResult.ok(call, "已写入 " + bytes.length + " 字节 → " + target);
        } catch (ToolException e) {
            return ToolResult.error(call, e.getMessage());
        } catch (IOException e) {
            return ToolResult.error(call, "写入文件失败: " + ReadFileTool.safe(e));
        }
    }
}
