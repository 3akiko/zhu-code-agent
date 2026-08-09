package com.zhubao.tool;

import com.zhubao.diff.DiffGenerator;
import com.zhubao.tool.builtin.BashTool;
import com.zhubao.tool.builtin.EditFileTool;
import com.zhubao.tool.builtin.GlobTool;
import com.zhubao.tool.builtin.GrepTool;
import com.zhubao.tool.builtin.ReadFileTool;
import com.zhubao.tool.builtin.WriteFileTool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内置工具注册表（spec F2）：注册 6 个内置工具，按名查找，提供 tools 定义源。
 */
public final class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(PathGuard guard, int diffMaxLines) {
        DiffGenerator diff = new DiffGenerator(diffMaxLines);
        register(new ReadFileTool(guard));
        register(new WriteFileTool(guard, diff));
        register(new EditFileTool(guard, diff));
        register(new BashTool(guard));
        register(new GrepTool(guard));
        register(new GlobTool(guard));
    }

    private void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    /** 全部内置工具（注册顺序） */
    public List<Tool> all() {
        return List.copyOf(tools.values());
    }

    /** 按名查找；未知工具返回 empty（调用方构造错误结果回填） */
    public Optional<Tool> byName(String name) {
        return Optional.ofNullable(tools.get(name));
    }
}
