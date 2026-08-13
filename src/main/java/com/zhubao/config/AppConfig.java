package com.zhubao.config;

import java.nio.file.Path;
import java.util.List;

/**
 * 应用配置根对象。
 *
 * @param providers           供应商列表（至少一个）
 * @param sessionsDir         会话保存目录（YAML 中不配置，M1 固定为 ~/.zhu-code-agent/sessions）
 * @param toolMaxCallsPerTurn  单轮 agent 循环工具调用上限（M2，YAML: tool.max_calls_per_turn，默认 60）
 * @param uiToolPreviewLines   工具结果预览行数（M2，YAML: ui.tool_preview_lines，默认 5）
 * @param uiDiffMaxLines       diff 展示最大行数（M3，YAML: ui.diff_max_lines，默认 200）
 * @param contextAlertThreshold  上下文占用告警阈值（M4，YAML: context.alert_threshold，默认 0.8）
 * @param contextCompactThreshold 自动压缩触发阈值（M4，YAML: context.compact_threshold，默认 0.9）
 * @param contextCompactTarget    压缩目标水位（M4，YAML: context.compact_target，默认 0.6）
 * @param contextSnipEnabled      压缩时是否启用本地瘦身（M4，YAML: context.snip_enabled，默认 true）
 * @param contextKeepRecentTurns  压缩折叠后保留的最近轮数（M4，YAML: context.keep_recent_turns，默认 8）
 * @param agentMaxDepth           子任务嵌套深度上限（M5，YAML: agent.max_subagent_depth，默认 2：父→子→孙封顶）
 * @param agentMaxParallel        并行子任务上限（M5，YAML: agent.max_parallel_subagents，默认 4）
 * @param agentMaxStepsPerSubtask 单个子任务步数上限（M5，YAML: agent.max_steps_per_subagent，默认 30）
 */
public record AppConfig(
        List<ProviderConfig> providers,
        Path sessionsDir,
        int toolMaxCallsPerTurn,
        int uiToolPreviewLines,
        int uiDiffMaxLines,
        double contextAlertThreshold,
        double contextCompactThreshold,
        double contextCompactTarget,
        boolean contextSnipEnabled,
        int contextKeepRecentTurns,
        int agentMaxDepth,
        int agentMaxParallel,
        int agentMaxStepsPerSubtask) {

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

    /** 上下文占用告警阈值默认值（M4，spec F2） */
    public static final double DEFAULT_CONTEXT_ALERT_THRESHOLD = 0.8;
    /** 自动压缩触发阈值默认值（M4，spec F3） */
    public static final double DEFAULT_CONTEXT_COMPACT_THRESHOLD = 0.9;
    /** 压缩目标水位默认值（M4，spec F3）：压缩后占用应回落到该比例以下 */
    public static final double DEFAULT_CONTEXT_COMPACT_TARGET = 0.6;
    /** 压缩本地瘦身开关默认值（M4，spec F3） */
    public static final boolean DEFAULT_CONTEXT_SNIP_ENABLED = true;
    /** 压缩折叠后保留的最近轮数默认值（M4，spec F3） */
    public static final int DEFAULT_CONTEXT_KEEP_RECENT_TURNS = 8;

    /** 子任务嵌套深度上限默认值（M5，spec F3.5）：父→子→孙封顶 */
    public static final int DEFAULT_AGENT_MAX_DEPTH = 2;
    /** 并行子任务上限默认值（M5，spec F3.5） */
    public static final int DEFAULT_AGENT_MAX_PARALLEL = 4;
    /** 单个子任务步数上限默认值（M5，spec F3.5/F3.10） */
    public static final int DEFAULT_AGENT_MAX_STEPS_PER_SUBTASK = 30;
}
