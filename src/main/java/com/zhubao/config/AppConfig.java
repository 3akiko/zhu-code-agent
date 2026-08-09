package com.zhubao.config;

import java.nio.file.Path;
import java.util.List;

/**
 * 应用配置根对象。
 *
 * @param providers           供应商列表（至少一个）
 * @param sessionsDir         会话保存目录（YAML 中不配置，M1 固定为 ~/.zhu-code-agent/sessions）
 * @param toolMaxCallsPerTurn 单轮 agent 循环工具调用上限（M2，YAML: tool.max_calls_per_turn，默认 60）
 * @param uiToolPreviewLines  工具结果预览行数（M2，YAML: ui.tool_preview_lines，默认 5）
 */
public record AppConfig(
        List<ProviderConfig> providers,
        Path sessionsDir,
        int toolMaxCallsPerTurn,
        int uiToolPreviewLines,
        int uiDiffMaxLines) {

    /** 会话目录默认值：~/.zhu-code-agent/sessions */
    public static Path defaultSessionsDir() {
        return Path.of(System.getProperty("user.home"), ".zhu-code-agent", "sessions");
    }

    /** 单轮工具调用上限默认值 */
    public static final int DEFAULT_TOOL_MAX_CALLS_PER_TURN = 60;

    /** 工具结果预览行数默认值 */
    public static final int DEFAULT_UI_TOOL_PREVIEW_LINES = 5;

    /** diff 展示最大行数默认值（M3，spec N1） */
    public static final int DEFAULT_UI_DIFF_MAX_LINES = 200;
}
