package com.pophie.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务 JSON 序列化工具。
 * Jackson 默认输出 UTF-8 中文字符（等价于 Python json.dumps(ensure_ascii=False)）。
 * 采用 snake_case 命名策略：POJO（RobotOutput / SttResult / VoiceProsody 等）字段输出为 snake_case，
 * 与原 Pydantic model_dump() 契约一致；Map 字面量键不受影响。
 */
public final class JsonUtil {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private JsonUtil() {}

    public static String dumps(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("json dumps failed", e);
        }
    }

    /** 解析为 List；失败回退空 List（对应 Python row_to_dict 容错）。 */
    @SuppressWarnings("unchecked")
    public static List<Object> parseList(String raw) {
        if (raw == null || raw.isEmpty()) return new ArrayList<>();
        try {
            Object v = MAPPER.readValue(raw, Object.class);
            if (v instanceof List) return (List<Object>) v;
            return new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 解析为 Map；失败回退空 Map。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseMap(String raw) {
        if (raw == null || raw.isEmpty()) return new LinkedHashMap<>();
        try {
            Object v = MAPPER.readValue(raw, Object.class);
            if (v instanceof Map) return (Map<String, Object>) v;
            return new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    public static Map<String, Object> parseMapStrict(String raw) {
        try {
            return MAPPER.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("json parse failed", e);
        }
    }
}
