package com.zhubao.history;

import java.util.List;

/**
 * 回滚结果（M3，spec F3）。
 *
 * @param ok      是否成功（部分失败 → false）
 * @param message 用户可读摘要
 * @param actions 逐文件恢复动作（供 UI 展示与写回会话）
 */
public record RollbackResult(boolean ok, String message, List<String> actions) {

    /** 无可撤销检查点时返回 */
    public static RollbackResult none() {
        return new RollbackResult(false, "没有可撤销的修改", List.of());
    }
}
