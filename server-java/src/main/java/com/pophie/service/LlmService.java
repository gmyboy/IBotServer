package com.pophie.service;

import com.pophie.config.RuntimeConfigService;
import com.pophie.schema.Schemas;
import com.pophie.util.JsonUtil;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 客户端：OpenAI 兼容协议（适配 OpenAI / DeepSeek / Moonshot / Ollama 等）。
 * 逐函数对应 llm.py。
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger("pophie.llm");
    private static final Pattern JSON_BLOB = Pattern.compile("\\{[\\s\\S]*\\}");
    private static final Pattern FENCE_HEAD = Pattern.compile("^```[a-zA-Z]*\\n?");
    private static final Pattern FENCE_TAIL = Pattern.compile("\\n?```\\s*$");

    private final RuntimeConfigService cfg;

    public LlmService(RuntimeConfigService cfg) {
        this.cfg = cfg;
    }

    // ---------- 厂商扩展 / max_tokens ----------

    private void applyProviderExtras(Map<String, Object> payload) {
        Object thinking = cfg.llm().get("thinking");
        if (thinking instanceof Map<?, ?> t && t.get("type") != null
                && !"".equals(t.get("type").toString())) {
            payload.put("thinking", thinking);
        }
    }

    private Integer chatMaxTokens() {
        Map<String, Object> chat = cfg.chat();
        Object raw = chat.get("max_tokens");
        if (raw == null) raw = cfg.llm().get("max_tokens");
        if (raw == null) return null;
        try {
            int val = (raw instanceof Number n) ? n.intValue() : Integer.parseInt(raw.toString().trim());
            return val > 0 ? val : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- 同步 chat ----------

    public String chat(List<Map<String, Object>> messages, Double temperature,
                       Object responseFormat, boolean userFacing) {
        Map<String, Object> llm = cfg.llm();
        String base = RuntimeConfigService.str(llm, "base_url", "");
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String url = base + "/chat/completions";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", RuntimeConfigService.str(llm, "model", ""));
        payload.put("messages", messages);
        payload.put("temperature", temperature == null
                ? RuntimeConfigService.dbl(llm, "temperature", 0.7) : temperature);
        if (responseFormat != null) payload.put("response_format", responseFormat);
        Integer maxTokens = chatMaxTokens();
        if (maxTokens != null) payload.put("max_tokens", maxTokens);
        applyProviderExtras(payload);

        int timeout = RuntimeConfigService.integer(llm, "timeout", 60);
        long t0 = System.currentTimeMillis();
        try (CloseableHttpClient client = buildClient(timeout)) {
            HttpPost post = new HttpPost(url);
            post.setHeader("Authorization", "Bearer " + RuntimeConfigService.str(llm, "api_key", ""));
            post.setHeader("Content-Type", "application/json");
            post.setEntity(new StringEntity(JsonUtil.dumps(payload), ContentType.APPLICATION_JSON));

            try (CloseableHttpResponse resp = client.execute(post)) {
                int status = resp.getCode();
                String bodyStr = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
                if (status >= 400) {
                    throw new LlmException("LLM 调用失败：HTTP " + status + " " + bodyStr);
                }
                Map<String, Object> data = JsonUtil.parseMapStrict(bodyStr);
                Map<String, Object> choice = firstChoice(data);
                Map<String, Object> msg = asMap(choice.get("message"));
                String content = trim(asStr(msg.get("content")));
                String reasoning = trim(asStr(msg.get("reasoning_content")));
                if (content.isEmpty()) {
                    log.warn("[llm] content 为空 finish_reason={} usage={}",
                            choice.get("finish_reason"), data.get("usage"));
                }
                if (userFacing) {
                    if (content.isEmpty() && !reasoning.isEmpty()) {
                        log.warn("[llm] 模型仅返回 reasoning_content（{} chars），不展示给用户", reasoning.length());
                    }
                    // content = content or ""
                } else if (content.isEmpty()) {
                    content = !reasoning.isEmpty() ? reasoning : trim(asStr(choice.get("text")));
                }
                log.info("[llm] <- ok in {}ms usage={} reply_chars={}",
                        System.currentTimeMillis() - t0, data.get("usage"),
                        content == null ? 0 : content.length());
                return content == null ? "" : content;
            }
        } catch (LlmException e) {
            log.error("[llm] <- 失败 in {}ms: {}", System.currentTimeMillis() - t0, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("[llm] <- 失败 in {}ms: {}", System.currentTimeMillis() - t0, e.getMessage());
            throw new LlmException("LLM 调用失败：" + e.getMessage(), e);
        }
    }

    // ---------- JSON 解析 ----------

    /** 对应 _parse_json_text：尽可能从模型输出中解析 JSON 对象。失败抛 RuntimeException。 */
    public Map<String, Object> parseJsonText(String textIn) {
        String text = textIn == null ? "" : textIn.trim();
        if (text.isEmpty()) throw new RuntimeException("empty");
        if (text.startsWith("```")) {
            text = FENCE_HEAD.matcher(text).replaceFirst("");
            text = FENCE_TAIL.matcher(text).replaceFirst("").trim();
        }
        Map<String, Object> direct = tryParseMap(text);
        if (direct != null) return direct;
        Matcher m = JSON_BLOB.matcher(text);
        if (m.find()) {
            String blob = m.group(0);
            Map<String, Object> parsed = tryParseMap(blob);
            if (parsed != null) return parsed;
            // 尝试补全被截断的 JSON
            if (!blob.stripTrailing().endsWith("}")) {
                for (String suffix : new String[]{"\"}", "\"}", "null}", "null}}", "}}"}) {
                    Map<String, Object> p2 = tryParseMap(blob + suffix);
                    if (p2 != null) return p2;
                }
            }
        }
        throw new RuntimeException("invalid json");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> tryParseMap(String s) {
        try {
            Object v = JsonUtil.MAPPER.readValue(s, Object.class);
            if (v instanceof Map) return (Map<String, Object>) v;
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 对应 ensure_dialogue_text：丢弃内心独白式回复。 */
    public String ensureDialogueText(String textIn) {
        String t = textIn == null ? "" : textIn.trim();
        if (t.isEmpty()) return t;
        if (Schemas.looksLikeInternalMonologue(t)) {
            log.warn("[llm] 内心独白式回复已丢弃 ({} chars)", t.length());
            return "";
        }
        return t;
    }

    // ---------- chat_json ----------

    public Map<String, Object> chatJson(List<Map<String, Object>> messages, Double temperature, boolean userFacing) {
        Map<String, Object> llm = cfg.llm();
        double temp = temperature == null ? RuntimeConfigService.dbl(llm, "chat_temperature", 0.3) : temperature;
        Object responseFormat = RuntimeConfigService.bool(llm, "json_mode", false)
                ? Map.of("type", "json_object") : null;
        String text = chat(messages, temp, responseFormat, userFacing).trim();
        if (text.isEmpty()) throw new LlmException("模型返回空内容");

        try {
            Map<String, Object> data = parseJsonText(text);
            if (userFacing && data.get("text") instanceof String s) {
                data.put("text", ensureDialogueText(s));
            }
            return data;
        } catch (Exception ignore) {
            // fall through
        }

        if (!text.startsWith("{")) {
            String plain = userFacing ? ensureDialogueText(text) : text;
            if (plain != null && !plain.isEmpty()) {
                log.info("[llm] 模型返回纯文本，本地包装为结构化回复 ({} chars)", plain.length());
                return wrapPlain(plain);
            }
        }
        String preview = text.substring(0, Math.min(200, text.length()));
        log.error("[llm] 无法解析模型输出：{}", preview);
        throw new LlmException("模型输出无法解析：" + preview);
    }

    public Map<String, Object> chatJson(List<Map<String, Object>> messages) {
        return chatJson(messages, null, true);
    }

    /** 对应 parse_chat_json。 */
    public Map<String, Object> parseChatJson(String textIn, boolean userFacing) {
        String text = textIn == null ? "" : textIn.trim();
        if (text.isEmpty()) throw new LlmException("模型返回空内容");
        try {
            Map<String, Object> data = parseJsonText(text);
            if (userFacing && data.get("text") instanceof String s) {
                data.put("text", ensureDialogueText(s));
            }
            return data;
        } catch (Exception ignore) {
            // fall through
        }
        if (!text.startsWith("{")) {
            String plain = userFacing ? ensureDialogueText(text) : text;
            if (plain != null && !plain.isEmpty()) {
                return wrapPlain(plain);
            }
        }
        String preview = text.substring(0, Math.min(200, text.length()));
        throw new LlmException("模型输出无法解析：" + preview);
    }

    private Map<String, Object> wrapPlain(String plain) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", plain);
        m.put("facial_expression", "neutral");
        m.put("voice", null);
        m.put("gesture", null);
        m.put("posture", null);
        return m;
    }

    // ---------- 流式 ----------

    /**
     * 对应 iter_chat_stream：流式调用，逐块回调 content delta，返回完整内容。
     */
    public String streamChat(List<Map<String, Object>> messages, Double temperature,
                             boolean userFacing, Consumer<String> onDelta) {
        Map<String, Object> llm = cfg.llm();
        double temp = temperature == null ? RuntimeConfigService.dbl(llm, "chat_temperature", 0.3) : temperature;
        String base = RuntimeConfigService.str(llm, "base_url", "");
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String url = base + "/chat/completions";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", RuntimeConfigService.str(llm, "model", ""));
        payload.put("messages", messages);
        payload.put("temperature", temp);
        payload.put("stream", true);
        Integer maxTokens = chatMaxTokens();
        if (maxTokens != null) payload.put("max_tokens", maxTokens);
        applyProviderExtras(payload);

        int timeout = RuntimeConfigService.integer(llm, "timeout", 60);
        long t0 = System.currentTimeMillis();
        StringBuilder full = new StringBuilder();
        try (CloseableHttpClient client = buildClient(timeout)) {
            HttpPost post = new HttpPost(url);
            post.setHeader("Authorization", "Bearer " + RuntimeConfigService.str(llm, "api_key", ""));
            post.setHeader("Content-Type", "application/json");
            post.setEntity(new StringEntity(JsonUtil.dumps(payload), ContentType.APPLICATION_JSON));

            try (CloseableHttpResponse resp = client.execute(post)) {
                if (resp.getCode() >= 400) {
                    String err = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
                    throw new LlmException("LLM 流式调用失败：HTTP " + resp.getCode() + " " + err);
                }
                InputStream is = resp.getEntity().getContent();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || !line.startsWith("data: ")) continue;
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) break;
                    Map<String, Object> chunk = tryParseMap(data);
                    if (chunk == null) continue;
                    Map<String, Object> choice = firstChoice(chunk);
                    Map<String, Object> delta = asMap(choice.get("delta"));
                    String content = asStr(delta.get("content"));
                    if (content != null && !content.isEmpty()) {
                        full.append(content);
                        onDelta.accept(content);
                    }
                }
            }
            log.info("[llm] <- stream ok in {}ms reply_chars={}",
                    System.currentTimeMillis() - t0, full.length());
            return full.toString();
        } catch (LlmException e) {
            log.error("[llm] <- stream 失败 in {}ms: {}", System.currentTimeMillis() - t0, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("[llm] <- stream 失败 in {}ms: {}", System.currentTimeMillis() - t0, e.getMessage());
            throw new LlmException("LLM 流式调用失败：" + e.getMessage(), e);
        }
    }

    // ---------- 内部工具 ----------

    private CloseableHttpClient buildClient(int timeoutSec) {
        RequestConfig rc = RequestConfig.custom()
                .setResponseTimeout(Timeout.ofSeconds(timeoutSec))
                .setConnectionRequestTimeout(Timeout.ofSeconds(timeoutSec))
                .build();
        return HttpClients.custom().setDefaultRequestConfig(rc).build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstChoice(Map<String, Object> data) {
        Object choices = data.get("choices");
        if (choices instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map) {
            return (Map<String, Object>) l.get(0);
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    private String asStr(Object o) {
        return o == null ? null : o.toString();
    }

    private String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
