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
import java.util.List;
import java.util.Map;

/**
 * read_file：读取文件，支持 offset/limit 行范围（spec F2；配合截断后「重新调用工具取更多」）。
 * 输出上限 200KB（超出截断并标注），避免超大文件灌爆上下文。
 */
public final class ReadFileTool implements Tool {

    static final int OUTPUT_CAP_CHARS = 200_000;
    static final String TRUNCATED_MARK = "\n…（已截断，完整内容可重新调用 read_file 读取）";

    private final PathGuard guard;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ReadFileTool(PathGuard guard) {
        this.guard = guard;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "读取文件内容；可选 offset（起始行，1 起）与 limit（读取行数）读取文件的部分行。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties")
                .putObject("path").put("type", "string").put("description", "文件路径（相对工作区或绝对路径，须在工作区内）")
                .putObject("offset").put("type", "integer").put("description", "起始行号（1 起，默认 1）")
                .putObject("limit").put("type", "integer").put("description", "最多读取的行数（默认全部）");
        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        Map<String, Object> args = call.arguments();
        String path = args.get("path") == null ? null : String.valueOf(args.get("path"));
        if (path == null || path.isBlank()) {
            return ToolResult.error(call, "read_file 缺少参数 path");
        }
        try {
            Path target = guard.resolveInWorkspace(path);
            if (!Files.exists(target)) {
                return ToolResult.error(call, "文件不存在: " + path);
            }
            if (Files.isDirectory(target)) {
                return ToolResult.error(call, "是目录不是文件: " + path);
            }
            List<String> lines = Files.readAllLines(target, StandardCharsets.UTF_8);

            int offset = intArg(args, "offset", 1);
            int limit = intArg(args, "limit", -1);
            int from = Math.max(1, offset);
            int to = limit > 0 ? Math.min(lines.size(), from + limit - 1) : lines.size();
            if (from > lines.size()) {
                return ToolResult.ok(call, "");
            }
            StringBuilder sb = new StringBuilder();
            for (int i = from - 1; i < to; i++) {
                sb.append(lines.get(i)).append('\n');
            }
            String content = sb.toString();
            if (content.length() > OUTPUT_CAP_CHARS) {
                content = content.substring(0, OUTPUT_CAP_CHARS) + TRUNCATED_MARK;
            }
            return ToolResult.ok(call, content);
        } catch (ToolException e) {
            return ToolResult.error(call, e.getMessage());
        } catch (IOException e) {
            return ToolResult.error(call, "读取文件失败: " + safe(e));
        }
    }

    static int intArg(Map<String, Object> args, String key, int def) {
        Object v = args.get(key);
        if (v == null) {
            return def;
        }
        try {
            return (int) Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static String safe(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
