package com.pophie.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务运行时配置：完整移植 config.py。
 * - 从 config.yaml 读取 llm/memory/server/chat/speech 五段
 * - .env 与环境变量覆盖密钥（LLM_API_KEY / DASHSCOPE_API_KEY / ADMIN_TOKEN）
 * - admin 热更新：脱敏读取、深合并保留密钥、写回 YAML、运行时 reload
 */
@Service
public class RuntimeConfigService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfigService.class);
    public static final String SECRET_MASK = "***";

    @Value("${pophie.config-path:./config.yaml}")
    private String configPath;

    private volatile Map<String, Object> config = new LinkedHashMap<>();
    private Map<String, String> dotenv = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        this.config = loadConfig();
    }

    private Path configFile() {
        return Path.of(configPath).toAbsolutePath();
    }

    private Path exampleFile() {
        Path p = configFile().getParent();
        return p == null ? Path.of("config.example.yaml") : p.resolve("config.example.yaml");
    }

    private Path envFile() {
        Path p = configFile().getParent();
        return p == null ? Path.of(".env") : p.resolve(".env");
    }

    // ---------- 嵌套 Map 读写（对应 _get_nested / _set_nested） ----------

    @SuppressWarnings("unchecked")
    private static Object getNested(Map<String, Object> data, List<String> path, Object def) {
        Object cur = data;
        for (String key : path) {
            if (!(cur instanceof Map)) return def;
            cur = ((Map<String, Object>) cur).get(key);
            if (cur == null) return def;
        }
        return cur;
    }

    @SuppressWarnings("unchecked")
    private static void setNested(Map<String, Object> data, List<String> path, Object value) {
        Map<String, Object> cur = data;
        for (int i = 0; i < path.size() - 1; i++) {
            Object nxt = cur.get(path.get(i));
            if (!(nxt instanceof Map)) {
                nxt = new LinkedHashMap<String, Object>();
                cur.put(path.get(i), nxt);
            }
            cur = (Map<String, Object>) nxt;
        }
        cur.put(path.get(path.size() - 1), value);
    }

    // ---------- 加载（对应 _load_dotenv / _apply_env_secrets / load_config） ----------

    private void loadDotenv() {
        dotenv = new LinkedHashMap<>();
        Path env = envFile();
        if (!Files.isRegularFile(env)) return;
        try {
            for (String raw : Files.readAllLines(env, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) continue;
                int idx = line.indexOf('=');
                String key = line.substring(0, idx).trim();
                String value = line.substring(idx + 1).trim();
                if ((value.startsWith("'") && value.endsWith("'")) || (value.startsWith("\"") && value.endsWith("\""))) {
                    value = value.substring(1, value.length() - 1);
                }
                if (!key.isEmpty()) dotenv.put(key, value);
            }
        } catch (IOException e) {
            log.warn("[config] 读取 .env 失败: {}", e.getMessage());
        }
    }

    /** 环境变量优先于 .env（对应 Python：dotenv 仅在 os.environ 缺失时写入）。 */
    private String env(String key) {
        String v = System.getenv(key);
        if (v != null) return v;
        return dotenv.get(key);
    }

    private void applyEnvSecrets(Map<String, Object> cfg) {
        String llmKey = trim(env("LLM_API_KEY"));
        if (!llmKey.isEmpty()) setNested(cfg, List.of("llm", "api_key"), llmKey);
        String dashscope = trim(env("DASHSCOPE_API_KEY"));
        if (!dashscope.isEmpty()) {
            setNested(cfg, List.of("speech", "tts", "api_key"), dashscope);
            setNested(cfg, List.of("speech", "stt", "api_key"), dashscope);
        }
        String adminToken = trim(env("ADMIN_TOKEN"));
        if (!adminToken.isEmpty()) setNested(cfg, List.of("server", "admin_token"), adminToken);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> loadConfig() {
        loadDotenv();
        Path file = configFile();
        if (!Files.isRegularFile(file)) {
            String hint = "未找到 " + file.getFileName() + "。请复制 " + exampleFile().getFileName()
                    + " 为 config.yaml，或在项目根目录创建 .env 填入密钥。";
            throw new IllegalStateException(hint);
        }
        Map<String, Object> cfg;
        try {
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(Files.readString(file, StandardCharsets.UTF_8));
            cfg = loaded instanceof Map ? (Map<String, Object>) loaded : new LinkedHashMap<>();
        } catch (IOException e) {
            throw new IllegalStateException("读取 config.yaml 失败: " + e.getMessage(), e);
        }
        applyEnvSecrets(cfg);
        return cfg;
    }

    public void reloadRuntimeConfig() {
        this.config = loadConfig();
    }

    // ---------- 运行时分段访问 ----------

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(String name) {
        Object v = config.get(name);
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    public Map<String, Object> raw() { return config; }
    public Map<String, Object> llm() { return section("llm"); }
    public Map<String, Object> memory() { return section("memory"); }
    public Map<String, Object> server() { return section("server"); }
    public Map<String, Object> chat() { return section("chat"); }

    public Map<String, Object> speech() {
        Object v = config.get("speech");
        if (v instanceof Map) {
            //noinspection unchecked
            return (Map<String, Object>) v;
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("enabled", false);
        return d;
    }

    // ---------- 类型化取值辅助 ----------

    public static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : v.toString();
    }

    public static double dbl(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return def; }
    }

    public static int integer(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString().trim()); } catch (Exception e) { return def; }
    }

    public static Integer integerOrNull(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString().trim()); } catch (Exception e) { return null; }
    }

    public static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> sub(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    // ---------- 脱敏 / 热更新（对应 _mask_secrets / save_config / _preserve_secrets） ----------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> src) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : src.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map) out.put(e.getKey(), deepCopy((Map<String, Object>) v));
            else if (v instanceof List) out.put(e.getKey(), new ArrayList<>((List<Object>) v));
            else out.put(e.getKey(), v);
        }
        return out;
    }

    private Map<String, Object> maskSecrets(Map<String, Object> cfg) {
        Map<String, Object> masked = deepCopy(cfg);
        if (getNested(masked, List.of("llm", "api_key"), null) != null
                && !"".equals(getNested(masked, List.of("llm", "api_key"), ""))) {
            setNested(masked, List.of("llm", "api_key"), SECRET_MASK);
        }
        if (getNested(masked, List.of("server", "admin_token"), null) != null
                && !"".equals(getNested(masked, List.of("server", "admin_token"), ""))) {
            setNested(masked, List.of("server", "admin_token"), SECRET_MASK);
        }
        if (getNested(masked, List.of("speech", "tts", "api_key"), null) != null
                && !"".equals(getNested(masked, List.of("speech", "tts", "api_key"), ""))) {
            setNested(masked, List.of("speech", "tts", "api_key"), SECRET_MASK);
        }
        return masked;
    }

    public Map<String, Object> getConfigForAdmin() {
        return maskSecrets(loadConfig());
    }

    private static boolean isMasked(Object value) {
        return value == null || "".equals(value) || SECRET_MASK.equals(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> patch) {
        Map<String, Object> merged = deepCopy(base);
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            Object value = e.getValue();
            Object cur = merged.get(e.getKey());
            if (value instanceof Map && cur instanceof Map) {
                merged.put(e.getKey(), deepMerge((Map<String, Object>) cur, (Map<String, Object>) value));
            } else {
                merged.put(e.getKey(), value);
            }
        }
        return merged;
    }

    private void preserveSecrets(Map<String, Object> newCfg, Map<String, Object> oldCfg) {
        for (List<String> path : List.of(
                List.of("llm", "api_key"),
                List.of("server", "admin_token"),
                List.of("speech", "tts", "api_key"))) {
            if (isMasked(getNested(newCfg, path, null))) {
                Object old = getNested(oldCfg, path, null);
                if (old != null && !"".equals(old)) setNested(newCfg, path, old);
            }
        }
    }

    /** 对应 save_config：写入 config.yaml 并热更新进程内配置。返回脱敏后的配置。 */
    public synchronized Map<String, Object> saveConfig(Map<String, Object> data) {
        Map<String, Object> current = loadConfig();
        Map<String, Object> merged = deepMerge(current, data);
        preserveSecrets(merged, current);
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setAllowUnicode(true);
        options.setPrettyFlow(false);
        Yaml yaml = new Yaml(options);
        try (Writer w = Files.newBufferedWriter(configFile(), StandardCharsets.UTF_8)) {
            yaml.dump(merged, w);
        } catch (IOException e) {
            throw new IllegalStateException("写入 config.yaml 失败: " + e.getMessage(), e);
        }
        reloadRuntimeConfig();
        return maskSecrets(merged);
    }

    public List<Map<String, Object>> adminSchema() {
        return AdminConfigSchema.build();
    }

    public String configPathString() {
        return configFile().toString();
    }
}
