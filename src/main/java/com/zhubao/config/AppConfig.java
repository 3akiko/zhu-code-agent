package com.zhubao.config;

import java.nio.file.Path;
import java.util.List;

/**
 * 应用配置根对象。
 *
 * @param providers   供应商列表（至少一个）
 * @param sessionsDir 会话保存目录（YAML 中不配置，M1 固定为 ~/.zhu-code-agent/sessions）
 */
public record AppConfig(List<ProviderConfig> providers, Path sessionsDir) {

    /** 会话目录默认值：~/.zhu-code-agent/sessions */
    public static Path defaultSessionsDir() {
        return Path.of(System.getProperty("user.home"), ".zhu-code-agent", "sessions");
    }
}
