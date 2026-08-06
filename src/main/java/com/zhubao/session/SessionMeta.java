package com.zhubao.session;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 会话元数据：标识、时间、标题摘要、消息数、provider 快照。
 */
public record SessionMeta(
        String id,
        Instant createdAt,
        Instant updatedAt,
        String title,
        int messageCount,
        ProviderSnapshot provider) {

    private static final DateTimeFormatter ID_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 生成会话 id：时间戳 + 4 位随机十六进制（可用作文件名，安全字符） */
    public static String newId() {
        String ts = ID_TS.format(LocalDateTime.now());
        String rand = Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000));
        return ts + "-" + rand;
    }
}
