package com.zhubao.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhubao.diff.DiffGenerator;
import com.zhubao.tool.PathGuard;
import com.zhubao.tool.RenderHint;
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
 * M3（spec F1）：覆写已有文件 → 结果内嵌 diff（renderHint=FULL）；新建 → 行数/字节数概览（PREVIEW）。
 * 覆写前读取旧内容仅限小文件（review P2-2）：超过 {@link #MAX_DIFF_READ_BYTES} 跳过 diff，避免全量读入内存。
 */
public final class WriteFileTool implements Tool {

    private final PathGuard guard;
    private final DiffGenerator diff;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 覆写前读取旧内容生成 diff 的大小上限（与快照上限一致，见 history.FileHistory.MAX_SNAPSHOT_BYTES）。
     * 避免覆写大文件时全量读入内存（spec N6 / review P2-2）。
     */
    private static final long MAX_DIFF_READ_BYTES = 10L * 1024 * 1024;

    public WriteFileTool(PathGuard guard, DiffGenerator diff) {
        this.guard = guard;
        this.diff = diff;
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
            boolean existed = Files.isRegularFile(target);
            boolean diffSkipped = false;
            String before = null;
            if (existed) {
                if (Files.size(target) > MAX_DIFF_READ_BYTES) {
                    diffSkipped = true; // 大文件：跳过旧内容读取与 diff（review P2-2）
                } else {
                    before = Files.readString(target, StandardCharsets.UTF_8);
                }
            }
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            Files.write(target, bytes);
            if (existed) {
                if (diffSkipped) {
                    return ToolResult.ok(call, "已覆写 " + bytes.length + " 字节 → " + target + "（文件过大，跳过 diff 展示）");
                }
                String diffText = diff.diff(before, content);
                String out = "已覆写 " + bytes.length + " 字节 → " + target
                        + (diffText.isEmpty() ? "" : "\n" + diffText);
                return ToolResult.ok(call, out, RenderHint.FULL);
            }
            int lines = content.isEmpty() ? 0 : content.split("\n", -1).length;
            return ToolResult.ok(call, "已创建 " + target + "（" + lines + " 行 / " + bytes.length + " 字节）");
        } catch (ToolException e) {
            return ToolResult.error(call, e.getMessage());
        } catch (IOException e) {
            return ToolResult.error(call, "写入文件失败: " + ReadFileTool.safe(e));
        }
    }
}
