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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * grep：在工作区内按文本/正则搜索，返回「相对路径:行号:内容」摘要（spec F2）。
 * 跳过 .git 与二进制文件；结果上限 200 条 + 输出上限 200KB。
 */
public final class GrepTool implements Tool {

    static final int MAX_RESULTS = 200;
    static final int OUTPUT_CAP_CHARS = 200_000;

    private final PathGuard guard;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public GrepTool(PathGuard guard) {
        this.guard = guard;
    }

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String description() {
        return "在工作区内按正则表达式搜索文件内容，返回匹配的「文件:行号:内容」列表；path 可选限定子目录。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties")
                .putObject("pattern").put("type", "string").put("description", "要匹配的正则表达式")
                .putObject("path").put("type", "string").put("description", "可选：限定搜索的子目录（默认工作区根）");
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
            return ToolResult.error(call, "grep 缺少参数 pattern");
        }
        Pattern regex;
        try {
            regex = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return ToolResult.error(call, "grep 正则不合法: " + e.getMessage());
        }
        Path base;
        try {
            String sub = args.get("path") == null ? null : String.valueOf(args.get("path"));
            base = (sub == null || sub.isBlank()) ? guard.root() : guard.resolveInWorkspace(sub);
            if (!Files.isDirectory(base)) {
                return ToolResult.error(call, "grep 路径不是目录: " + sub);
            }
        } catch (ToolException e) {
            return ToolResult.error(call, e.getMessage());
        }

        List<String> matches = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !isGitDir(p))
                    .forEach(p -> collect(p, base, regex, matches));
        } catch (IOException e) {
            return ToolResult.error(call, "grep 遍历失败: " + ReadFileTool.safe(e));
        }
        if (matches.isEmpty()) {
            return ToolResult.ok(call, "未找到匹配");
        }
        StringBuilder sb = new StringBuilder();
        for (String m : matches) {
            if (sb.length() + m.length() + 1 > OUTPUT_CAP_CHARS) {
                sb.append("\n…（结果过多，已截断）");
                break;
            }
            sb.append(m).append('\n');
        }
        return ToolResult.ok(call, sb.toString());
    }

    private void collect(Path file, Path base, Pattern regex, List<String> matches) {
        if (matches.size() >= MAX_RESULTS) {
            return;
        }
        try {
            String rel = base.relativize(file).toString();
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size() && matches.size() < MAX_RESULTS; i++) {
                if (regex.matcher(lines.get(i)).find()) {
                    String line = lines.get(i).trim();
                    if (line.length() > 200) {
                        line = line.substring(0, 200) + "…";
                    }
                    matches.add(rel + ":" + (i + 1) + ": " + line);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // 二进制/不可读文件跳过
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
