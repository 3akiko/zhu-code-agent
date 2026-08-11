package com.zhubao.context;

/**
 * 一次压缩的结果（M4，spec F3）。
 *
 * @param compacted         是否实际发生了改动（false 表示无低价值轮次可丢、无超限结果、也无需折叠）
 * @param foldedTurns       折叠进摘要的轮数（0 表示未折叠）
 * @param droppedTurns      本地瘦身丢弃的低价值轮数
 * @param truncatedResults  被截断的 tool_result 数
 * @param summary           生成的摘要文本（未折叠为空）
 * @param errorMessage      失败原因（成功为 null）
 */
public record CompactionResult(
        boolean compacted,
        int foldedTurns,
        int droppedTurns,
        int truncatedResults,
        String summary,
        String errorMessage) {

    public static CompactionResult none() {
        return new CompactionResult(false, 0, 0, 0, "", null);
    }

    public static CompactionResult ok(int foldedTurns, int droppedTurns, int truncatedResults, String summary) {
        return new CompactionResult(true, foldedTurns, droppedTurns, truncatedResults, summary, null);
    }

    public static CompactionResult error(String message) {
        return new CompactionResult(false, 0, 0, 0, "", message);
    }

    public boolean isError() {
        return errorMessage != null;
    }
}
