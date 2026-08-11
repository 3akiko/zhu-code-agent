package com.zhubao.context;

import com.zhubao.config.AppConfig;
import com.zhubao.session.SessionStore;

/**
 * 压缩参数（M4，spec F3）。
 *
 * @param target           压缩目标水位（占用应回落到的比例，默认 0.6）
 * @param snipEnabled      是否先做本地零成本瘦身（丢弃空/被拒低价值轮次）
 * @param keepRecentTurns  折叠后保留的最近轮数（默认 8）
 * @param toolResultCap    压缩时截断 tool_result 的字节上限（默认沿用落盘 64KB）
 */
public record CompactionOptions(double target, boolean snipEnabled, int keepRecentTurns, int toolResultCap) {

    /** 从 AppConfig 构建（spec N1：不新增依赖，仅配置映射） */
    public static CompactionOptions from(AppConfig config) {
        return new CompactionOptions(
                config.contextCompactTarget(),
                config.contextSnipEnabled(),
                config.contextKeepRecentTurns(),
                SessionStore.TOOL_RESULT_PERSIST_CAP);
    }
}
