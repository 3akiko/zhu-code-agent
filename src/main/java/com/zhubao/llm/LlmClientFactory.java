package com.zhubao.llm;

import com.zhubao.config.ProviderConfig;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Provider 工厂（spec F6）：按 protocol 创建对应客户端。
 * 新增后端 = 新增一个实现类 + 这里加一个分支，调用方（TUI）不变。
 *
 * <p>M5（spec F1.3）：按 provider 名**缓存复用**单例——主会话与子任务共享同一实例并发
 * {@code stream()}（per-call 状态持有者保证并发安全），避免每轮新建实例。
 * provider 名在配置加载时唯一校验，缓存键不会冲突。
 */
public final class LlmClientFactory {

    private static final ConcurrentHashMap<String, LlmClient> CACHE = new ConcurrentHashMap<>();

    private LlmClientFactory() {
    }

    /** 按 provider 名取（缓存）客户端实例；同名返回同一实例 */
    public static LlmClient create(ProviderConfig config) {
        return CACHE.computeIfAbsent(config.getName(), name -> createNew(config));
    }

    private static LlmClient createNew(ProviderConfig config) {
        return switch (config.getProtocol()) {
            case "anthropic" -> new AnthropicClient(config);
            case "openai" -> new OpenAiClient(config);
            default -> throw new LlmException("不支持的协议: " + config.getProtocol());
        };
    }

    /** 清空缓存（仅测试用：集成测试每个用例新建 mock server、端口不同，需隔离缓存） */
    public static void resetCache() {
        CACHE.clear();
    }
}
