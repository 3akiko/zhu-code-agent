package com.zhubao;

import com.zhubao.config.AppConfig;
import com.zhubao.config.ConfigException;
import com.zhubao.config.ConfigLoader;
import com.zhubao.tui.ChatApp;
import com.zhubao.tui.TuiException;

/**
 * 程序入口：解析 --config → 加载配置 → 启动 TUI。
 * 退出码：正常退出 0；配置/运行时错误 1。
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        String configPath = parseConfigPath(args);
        AppConfig config;
        try {
            config = ConfigLoader.load(configPath);
        } catch (ConfigException e) {
            System.err.println("配置错误: " + e.getMessage());
            System.exit(1);
            return;
        }
        try {
            new ChatApp(config).run();
        } catch (TuiException e) {
            System.err.println("终端错误: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("程序异常: " + safeMessage(e));
            System.exit(1);
        }
    }

    /** 解析 --config <path> 或 --config=<path>；缺省返回 null（走环境变量/默认路径） */
    private static String parseConfigPath(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("--config".equals(args[i]) && i + 1 < args.length) {
                return args[i + 1];
            }
            if (args[i].startsWith("--config=")) {
                return args[i].substring("--config=".length());
            }
        }
        return null;
    }

    private static String safeMessage(Exception e) {
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
    }
}
