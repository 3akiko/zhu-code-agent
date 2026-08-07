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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * glob：按通配符模式列出工作区内匹配路径（spec F2）。
 * 模式为相对工作区的 glob（如 src/**​/*.java）；结果上限 200 条。
 */
public final class GlobTool implements Tool {

    static final int MAX_RESULTS = 200;

    private final PathGuard guard;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public GlobTool(PathGuard guard) {
        this.guard = guard;
    }

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public String description() {
        return "按通配符模式（相对工作区）列出匹配的文件/目录路径，如 src/**/*.java。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties")
                .putObject("pattern").put("type", "string").put("description", "相对工作区的通配符模式");
        schema.putArray("required").add("pattern");
        return schema;
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        Map<String, Object> args = call.arguments();
        String pattern = args.get("pattern") == null ? null : String.valueOf(args.get("pattern"));
        if (pattern == null || pattern.isBlank()) {
            return ToolResult.error(call, "glob 缺少参数 pattern");
        }
        try {
            String norm = pattern.replace('\\', '/');
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + norm);
            List<String> matches = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(guard.root())) {
                walk.filter(p -> !p.equals(guard.root()))
                        .filter(p -> !isGitDir(p))
                        .forEach(p -> {
                            if (matches.size() >= MAX_RESULTS) {
                                return;
                            }
                            String rel = guard.root().relativize(p).toString().replace('\\', '/');
                            if (matcher.matches(Path.of(rel))) {
                                matches.add(rel);
                            }
                        });
            } catch (IOException e) {
                return ToolResult.error(call, "glob 遍历失败: " + ReadFileTool.safe(e));
            }
            if (matches.isEmpty()) {
                return ToolResult.ok(call, "未找到匹配路径");
            }
            return ToolResult.ok(call, String.join("\n", matches) + (matches.size() >= MAX_RESULTS ? "\n…（结果过多，已截断）" : ""));
        } catch (ToolException e) {
            return ToolResult.error(call, e.getMessage());
        } catch (Exception e) {
            return ToolResult.error(call, "glob 模式不合法: " + ReadFileTool.safe(e));
        }
    }

    private static boolean isGitDir(Path p) {
        for (Path part : p) {
            if (".git".equals(part.toString())) {
                return true;
            }
        }
        return false;
    }
}
