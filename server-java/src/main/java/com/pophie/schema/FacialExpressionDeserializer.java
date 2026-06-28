package com.pophie.schema;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/**
 * 归一化端侧表情 key（surprised/disgusted/fearful 等）与中文别名。
 * 对应 PerceptionInput._normalize_expression：解析失败返回 null，交由后续逻辑当作未提供。
 */
public class FacialExpressionDeserializer extends JsonDeserializer<FacialExpression> {

    @Override
    public FacialExpression deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) {
            return Schemas.parseFacialExpression(node.asText());
        }
        return null;
    }
}
