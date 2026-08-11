package com.zhubao.session;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 会话元数据：标识、时间、标题摘要、消息数、provider 快照、token 累计（M4，spec F1）。
 *
 * <p>totalInputTokens / totalOutputTokens 为会话生命周期内已成功完成请求的
 * 报告 usage 之和（单调累加、随 JSONL meta 行落盘，跨会话恢复后继续累加）。
 */
public record SessionMeta(
        String id,
        Instant createdAt,
        Instant updatedAt,
        String title,
        int messageCount,
        ProviderSnapshot provider,
        long totalInputTokens,
        long totalOutputTokens) {

    /** 兼容旧调用（M1–M3）：累计 token 默认 0 */
    public SessionMeta(String id, Instant createdAt, Instant updatedAt, String title,
                       int messageCount, ProviderSnapshot provider) {
        this(id, createdAt, updatedAt, title, messageCount, provider, 0L, 0L);
    }

    private static final DateTimeFormatter ID_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 生成会话 id：时间戳 + 4 位随机十六进制（可用作文件名，安全字符） */
    public static String newId() {
        String ts = ID_TS.format(LocalDateTime.now());
        String rand = Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000));
        return ts + "-" + rand;
    }
}
