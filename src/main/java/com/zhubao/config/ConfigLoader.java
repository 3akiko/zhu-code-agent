package com.zhubao.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置加载器：YAML → {@link AppConfig}。
 *
 * api_key 三级解析（spec F5）：
 * <ol>
 *   <li>直接值：配置里写死的字符串</li>
 *   <li>${ENV_VAR} 引用：形如 ${ANTHROPIC_API_KEY}，从环境变量展开；未设置则报可读错误</li>
 *   <li>环境变量回退：配置为空时按协议回退 ANTHROPIC_API_KEY / OPENAI_API_KEY</li>
 * </ol>
 */
public class ConfigLoader {

    /** 配置文件路径的环境变量名 */
    public static final String CONFIG_PATH_ENV = "ZHU_CODE_AGENT_CONFIG";
    /** 配置文件默认路径 */
    public static final Path DEFAULT_CONFIG_PATH = Path.of(System.getProperty("user.home"), ".zhu-code-agent", "config.yml");

    private static final Pattern ENV_REF = Pattern.compile("^\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}$");

    private static final Map<String, String> CONVENTION_ENV = Map.of(
            "anthropic", "ANTHROPIC_API_KEY",
            "openai", "OPENAI_API_KEY"
    );

    private static final Map<String, String> DEFAULT_BASE_URL = Map.of(
            "anthropic", "https://api.anthropic.com",
            "openai", "https://api.openai.com"
    );

    private static final Set<String> SUPPORTED_PROTOCOLS = Set.of("anthropic", "openai");

    /** 使用真实环境变量加载 */
    public static AppConfig load(String configPath) {
        return load(configPath, System.getenv());
    }

    /**
     * 加载配置。
     *
     * @param configPath 显式路径；为 null 时先看 ZHU_CODE_AGENT_CONFIG 环境变量，再取默认路径
     * @param env        环境变量视图（测试时注入，便于单测）
     */
    public static AppConfig load(String configPath, Map<String, String> env) {
        Path path = resolvePath(configPath, env);
        if (!Files.exists(path)) {
            throw new ConfigException("配置文件不存在: " + path);
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Object root = new Yaml().load(reader);
            return bind(root, env);
        } catch (IOException e) {
            throw new ConfigException("读取配置文件失败: " + path + "（" + e.getMessage() + "）", e);
        }
    }

    /** 解析配置文件路径：显式参数 > 环境变量 > 默认路径 */
    private static Path resolvePath(String configPath, Map<String, String> env) {
        if (configPath != null && !configPath.isBlank()) {
            return Path.of(configPath);
        }
        String envPath = env.get(CONFIG_PATH_ENV);
        if (envPath != null && !envPath.isBlank()) {
            return Path.of(envPath);
        }
        return DEFAULT_CONFIG_PATH;
    }

    /** YAML 根节点 → AppConfig */
    @SuppressWarnings("unchecked")
    private static AppConfig bind(Object root, Map<String, String> env) {
        if (!(root instanceof Map<?, ?> map)) {
            throw new ConfigException("配置文件格式错误：顶层应为映射（providers 列表）");
        }
        Object providersNode = map.get("providers");
        if (!(providersNode instanceof List<?> providerList) || providerList.isEmpty()) {
            throw new ConfigException("配置文件缺少有效的 providers 列表（至少一个供应商）");
        }

        List<ProviderConfig> providers = new ArrayList<>();
        Set<String> seenNames = new java.util.HashSet<>();
        for (Object item : providerList) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw new ConfigException("providers 列表项应为映射结构");
            }
            Map<String, Object> m = new LinkedHashMap<>();
            raw.forEach((k, v) -> m.put(String.valueOf(k), v));
            providers.add(bindProvider(m, env, seenNames));
        }

        Object sessionsNode = map.get("sessions_dir");
        Path sessionsDir = sessionsNode instanceof String s && !s.isBlank()
                ? Path.of(s)
                : AppConfig.defaultSessionsDir();
        return new AppConfig(List.copyOf(providers), sessionsDir);
    }

    /** 单项 YAML → ProviderConfig（含 api_key 解析与字段校验） */
    private static ProviderConfig bindProvider(Map<String, Object> m, Map<String, String> env, Set<String> seenNames) {
        String name = str(m, "name");
        if (name == null || name.isBlank()) {
            throw new ConfigException("providers 项缺少 name（供应商标识）");
        }
        if (!seenNames.add(name)) {
            throw new ConfigException("providers 中 name 重复: " + name);
        }

        String protocol = str(m, "protocol");
        if (protocol == null || !SUPPORTED_PROTOCOLS.contains(protocol)) {
            throw new ConfigException("provider[" + name + "] protocol 不合法（支持: anthropic, openai），实际: " + protocol);
        }

        String model = str(m, "model");
        if (model == null || model.isBlank()) {
            throw new ConfigException("provider[" + name + "] 缺少 model");
        }

        String baseUrl = str(m, "base_url");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL.get(protocol);
        }

        String apiKey = resolveApiKey(str(m, "api_key"), protocol, env, name);

        boolean thinking = Boolean.parseBoolean(str(m, "thinking"));

        ProviderConfig cfg = new ProviderConfig();
        cfg.setName(name);
        cfg.setProtocol(protocol);
        cfg.setModel(model);
        cfg.setBaseUrl(baseUrl);
        cfg.setApiKey(apiKey);
        cfg.setThinking(thinking);
        return cfg;
    }

    /** api_key 三级解析 */
    private static String resolveApiKey(String raw, String protocol, Map<String, String> env, String providerName) {
        if (raw != null && !raw.isBlank()) {
            Matcher matcher = ENV_REF.matcher(raw.trim());
            if (matcher.matches()) {
                String envName = matcher.group(1);
                String value = env.get(envName);
                if (value == null || value.isBlank()) {
                    throw new ConfigException("provider[" + providerName + "] 引用的环境变量未设置: " + envName);
                }
                return value;
            }
            return raw;
        }
        // 为空：回退到协议约定环境变量；仍为空则留空（API 调用时给出可读 401 错误）
        String convention = CONVENTION_ENV.get(protocol);
        return convention != null ? env.getOrDefault(convention, "") : "";
    }

    /** 取字符串值（缺失返回 null） */
    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
