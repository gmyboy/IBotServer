package com.pophie.schema;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/**
 * 兼容端侧传 {name, confidence} 结构，取 name；字符串则 strip。
 * 对应 PerceptionInput._normalize_identity。
 */
public class IdentityDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);
        if (node == null || node.isNull()) return null;
        if (node.isObject()) {
            JsonNode name = node.get("name");
            if (name == null || name.isNull()) name = node.get("label");
            if (name == null || name.isNull()) return null;
            String s = name.asText().trim();
            return s.isEmpty() ? null : s;
        }
        if (node.isTextual()) {
            String s = node.asText().trim();
            return s.isEmpty() ? null : s;
        }
        return null;
    }
}
