package com.zhubao.history;

import java.time.Instant;

/**
 * 文件写操作检查点（M3，spec F3）：
 * 每次 write_file / edit_file 执行前记录目标文件的写前状态。
 * 落盘于 ~/.zhu-code-agent/snapshots/<会话ID>/checkpoints.json（工具不可写、不占用工作区）。
 *
 * @param id            递增序号（如 "0001"），保证顺序
 * @param timestamp     快照时间
 * @param path          工作区内绝对路径（已解析）
 * @param existed       写前文件是否存在
 * @param beforeContent 写前内容（existed=false 时为 null）
 * @param summary       变更摘要（如 "edit_file: src/Foo.java"）
 */
public record FileCheckpoint(
        String id,
        Instant timestamp,
        String path,
        boolean existed,
        String beforeContent,
        String summary) {
}
