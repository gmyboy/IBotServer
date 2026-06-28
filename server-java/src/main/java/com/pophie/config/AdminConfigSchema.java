package com.pophie.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台配置表单 schema，逐字对应 config.py 的 ADMIN_CONFIG_SCHEMA（config.py:88-167）。
 */
public final class AdminConfigSchema {

    private AdminConfigSchema() {}

    private static Map<String, Object> field(List<String> path, String label, String type) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("path", path);
        f.put("label", label);
        f.put("type", type);
        return f;
    }

    private static Map<String, Object> field(List<String> path, String label, String type,
                                             Map<String, Object> extra) {
        Map<String, Object> f = field(path, label, type);
        f.putAll(extra);
        return f;
    }

    private static Map<String, Object> step(double step) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("step", step);
        return m;
    }

    private static Map<String, Object> hint(String hint) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hint", hint);
        return m;
    }

    private static Map<String, Object> section(String section, String title, List<Map<String, Object>> fields) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("section", section);
        s.put("title", title);
        s.put("fields", fields);
        return s;
    }

    public static List<Map<String, Object>> build() {
        List<Map<String, Object>> schema = new ArrayList<>();

        // llm
        List<Map<String, Object>> llm = new ArrayList<>();
        llm.add(field(List.of("base_url"), "API 地址", "text"));
        llm.add(field(List.of("api_key"), "API Key", "secret"));
        llm.add(field(List.of("model"), "模型", "text"));
        llm.add(field(List.of("timeout"), "超时（秒）", "number"));
        llm.add(field(List.of("temperature"), "温度", "number", step(0.1)));
        llm.add(field(List.of("chat_temperature"), "JSON 对话温度", "number", step(0.1)));
        llm.add(field(List.of("json_mode"), "JSON 模式", "boolean"));
        Map<String, Object> thinking = field(List.of("thinking", "type"), "思考模式", "select");
        thinking.put("options", Arrays.asList("disabled", "enabled"));
        llm.add(thinking);
        schema.add(section("llm", "LLM 模型", llm));

        // memory
        List<Map<String, Object>> mem = new ArrayList<>();
        mem.add(field(List.of("l2_session_cap"), "L2 会话上限", "number"));
        mem.add(field(List.of("l2_to_l3_importance"), "L2→L3 阈值", "number", step(0.01)));
        mem.add(field(List.of("l3_to_l4_importance"), "L3→L4 阈值", "number", step(0.01)));
        mem.add(field(List.of("l3_to_l4_reinforce_count"), "L3 巩固次数", "number"));
        mem.add(field(List.of("direct_l4_importance"), "直跃 L4 阈值", "number", step(0.01)));
        mem.add(field(List.of("importance_weight"), "重要性权重", "number", step(0.1)));
        mem.add(field(List.of("emotion_weight"), "情感权重", "number", step(0.1)));
        mem.add(field(List.of("emotion_direct_l4_intensity"), "情感直跃 L4", "number", step(0.01)));
        mem.add(field(List.of("recall_emotion_weight"), "召回情感权重", "number", step(0.1)));
        schema.add(section("memory", "记忆系统", mem));

        // server
        List<Map<String, Object>> server = new ArrayList<>();
        server.add(field(List.of("host"), "监听地址", "text", hint("修改后需重启服务")));
        server.add(field(List.of("port"), "端口", "number", hint("修改后需重启服务")));
        server.add(field(List.of("default_robot"), "默认 robot_id", "text"));
        server.add(field(List.of("admin_token"), "Admin Token", "secret"));
        schema.add(section("server", "服务器", server));

        // chat
        List<Map<String, Object>> chat = new ArrayList<>();
        chat.add(field(List.of("defer_side_tasks"), "后台处理记忆/提醒", "boolean"));
        chat.add(field(List.of("inline_tts"), "内联 TTS", "boolean"));
        chat.add(field(List.of("max_tokens"), "回复 token 上限", "number", hint("限制回复长度以加快 LLM 与 TTS")));
        chat.add(field(List.of("recent_turns"), "近期对话轮数", "number"));
        chat.add(field(List.of("recall_top_k"), "记忆召回条数", "number"));
        schema.add(section("chat", "聊天", chat));

        // speech
        List<Map<String, Object>> speech = new ArrayList<>();
        speech.add(field(List.of("enabled"), "启用语音", "boolean"));
        speech.add(field(List.of("stt", "model"), "STT 模型", "text"));
        speech.add(field(List.of("stt", "language"), "STT 语种", "text"));
        speech.add(field(List.of("stt", "sample_rate"), "STT 采样率", "number"));
        speech.add(field(List.of("stt", "timeout"), "STT 超时（秒）", "number"));
        speech.add(field(List.of("stt", "max_retries"), "STT 重试次数", "number"));
        speech.add(field(List.of("stt", "retry_delay_sec"), "STT 重试间隔（秒）", "number", step(0.1)));
        speech.add(field(List.of("tts", "api_key"), "DashScope API Key", "secret"));
        speech.add(field(List.of("tts", "base_url"), "TTS WebSocket 地址", "text"));
        speech.add(field(List.of("tts", "model"), "TTS 模型", "text"));
        speech.add(field(List.of("tts", "format"), "批量音频格式（/api/tts）", "text"));
        speech.add(field(List.of("tts", "stream_format"), "流式音频格式（/api/tts/stream）", "text"));
        speech.add(field(List.of("tts", "timeout"), "TTS 超时（秒）", "number"));
        speech.add(field(List.of("tts", "default_voice"), "默认音色", "voice_select", hint("切换后立即生效，无需重启")));
        speech.add(field(List.of("tts", "sample_rate"), "采样率", "number"));
        speech.add(field(List.of("tts", "max_retries"), "TTS 重试次数", "number"));
        speech.add(field(List.of("tts", "retry_delay_sec"), "重试间隔（秒）", "number", step(0.1)));
        schema.add(section("speech", "语音", speech));

        return schema;
    }
}
