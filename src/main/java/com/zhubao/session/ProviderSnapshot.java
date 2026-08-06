package com.zhubao.session;

/**
 * 会话记录的 provider 快照（spec F9）。
 * 刻意不含 apiKey：恢复会话时密钥始终从当前配置读取，密钥永不落盘（N4）。
 */
public record ProviderSnapshot(String name, String protocol, String model, String baseUrl) {
}
