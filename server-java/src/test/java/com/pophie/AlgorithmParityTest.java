package com.pophie;

import com.pophie.schema.FacialExpression;
import com.pophie.schema.RobotOutput;
import com.pophie.schema.Schemas;
import com.pophie.service.LlmService;
import com.pophie.service.StreamingReplyTextExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 纯算法对拍：与 Python 同输入应得同结果。 */
public class AlgorithmParityTest {

    @Test
    void streamingExtractorSplitsBySentence() {
        StreamingReplyTextExtractor ex = new StreamingReplyTextExtractor();
        // 分块喂入 JSON，text 字段含两句
        ex.feed("{\"text\":\"你好呀");
        List<String> a = ex.feed("。今天");
        assertEquals(List.of("你好呀。"), a);
        List<String> b = ex.feed("不错！\",\"facial_expression\":\"happy\"}");
        assertEquals(List.of("今天不错！"), b);
    }

    @Test
    void monologueDetection() {
        assertTrue(Schemas.looksLikeInternalMonologue("用户可能有点悲伤，我需要安慰对方"));
        assertTrue(Schemas.looksLikeInternalMonologue("结合抚摸和表情，用户似乎有点累"));
        assertFalse(Schemas.looksLikeInternalMonologue("你好呀，今天天气真不错"));
        assertFalse(Schemas.looksLikeInternalMonologue("短"));
    }

    @Test
    void parseJsonTextHandlesFenceAndTruncation() {
        LlmService llm = new LlmService(null);
        Map<String, Object> a = llm.parseJsonText("```json\n{\"text\":\"hi\"}\n```");
        assertEquals("hi", a.get("text"));
        // 从噪声中提取 JSON 对象（正则 \{[\s\S]*\}）
        Map<String, Object> b = llm.parseJsonText("前缀噪声 {\"text\":\"hi\"} 后缀");
        assertEquals("hi", b.get("text"));
    }

    @Test
    void facialExpressionAliases() {
        assertEquals(FacialExpression.surprise, Schemas.parseFacialExpression("surprised"));
        assertEquals(FacialExpression.sad, Schemas.parseFacialExpression("悲伤"));
        assertEquals(FacialExpression.angry, Schemas.parseFacialExpression("MAD"));
        assertEquals(FacialExpression.happy, Schemas.parseFacialExpression("happy"));
    }

    @Test
    void empathyAlignment() {
        // 用户悲伤 + 机器人 neutral → 纠正为 sad
        RobotOutput out = new RobotOutput();
        out.setText("没事的");
        out.setFacialExpression(FacialExpression.neutral);
        out.finalizeOutput();
        RobotOutput aligned = Schemas.alignOutputToUserPerception(out, FacialExpression.sad);
        assertEquals(FacialExpression.sad, aligned.getFacialExpression());
        assertEquals("温柔", aligned.getVoice().getTone());
        assertEquals("下沉", aligned.getVoice().getIntonation());
        assertEquals("慢", aligned.getVoice().getSpeed());
    }
}
