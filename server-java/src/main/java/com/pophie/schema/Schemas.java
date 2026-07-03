package com.pophie.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 表情/状态/手势映射表与解析工具，逐条对应 schemas.py。
 */
public final class Schemas {

    private Schemas() {}

    // ---------- RobotState ----------

    public static final Map<RobotState, String> ROBOT_STATE_LABELS = new LinkedHashMap<>();
    static {
        ROBOT_STATE_LABELS.put(RobotState.idle, "待机");
        ROBOT_STATE_LABELS.put(RobotState.gazing, "注视");
        ROBOT_STATE_LABELS.put(RobotState.listening, "聆听");
        ROBOT_STATE_LABELS.put(RobotState.thinking, "思考");
        ROBOT_STATE_LABELS.put(RobotState.happy, "高兴");
        ROBOT_STATE_LABELS.put(RobotState.confused, "困惑");
        ROBOT_STATE_LABELS.put(RobotState.sleepy, "困倦");
        ROBOT_STATE_LABELS.put(RobotState.sleeping, "睡眠");
        ROBOT_STATE_LABELS.put(RobotState.waking, "苏醒");
    }

    // 机器人输出表情 → FSM 状态的建议映射（robot_state 缺省时由此推导）
    public static final Map<FacialExpression, RobotState> EXPRESSION_TO_STATE = new LinkedHashMap<>();
    static {
        EXPRESSION_TO_STATE.put(FacialExpression.happy, RobotState.happy);
        EXPRESSION_TO_STATE.put(FacialExpression.neutral, RobotState.idle);
        EXPRESSION_TO_STATE.put(FacialExpression.sad, RobotState.sleepy);
        EXPRESSION_TO_STATE.put(FacialExpression.angry, RobotState.confused);
        EXPRESSION_TO_STATE.put(FacialExpression.disgust, RobotState.confused);
        EXPRESSION_TO_STATE.put(FacialExpression.fear, RobotState.confused);
        EXPRESSION_TO_STATE.put(FacialExpression.surprise, RobotState.confused);
    }

    public static RobotState parseRobotState(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty()) return null;
        return RobotState.ofValue(v);
    }

    public static RobotState robotStateForExpression(FacialExpression expr) {
        return EXPRESSION_TO_STATE.getOrDefault(expr, RobotState.idle);
    }

    // ---------- FacialExpression ----------

    public static final Map<FacialExpression, String> FACIAL_EXPRESSION_LABELS = new LinkedHashMap<>();
    static {
        FACIAL_EXPRESSION_LABELS.put(FacialExpression.angry, "恼怒");
        FACIAL_EXPRESSION_LABELS.put(FacialExpression.disgust, "厌恶");
        FACIAL_EXPRESSION_LABELS.put(FacialExpression.fear, "恐惧");
        FACIAL_EXPRESSION_LABELS.put(FacialExpression.happy, "开心");
        FACIAL_EXPRESSION_LABELS.put(FacialExpression.neutral, "中性");
        FACIAL_EXPRESSION_LABELS.put(FacialExpression.sad, "悲伤");
        FACIAL_EXPRESSION_LABELS.put(FacialExpression.surprise, "惊讶");
    }

    // 表情别名（LLM、网页端或 XBot 端侧可能返回中文标签或英文变体）
    public static final Map<String, FacialExpression> FACIAL_EXPRESSION_ALIASES = new LinkedHashMap<>();
    static {
        // 中文
        FACIAL_EXPRESSION_ALIASES.put("愤怒", FacialExpression.angry);
        FACIAL_EXPRESSION_ALIASES.put("恼怒", FacialExpression.angry);
        FACIAL_EXPRESSION_ALIASES.put("厌恶", FacialExpression.disgust);
        FACIAL_EXPRESSION_ALIASES.put("恐惧", FacialExpression.fear);
        FACIAL_EXPRESSION_ALIASES.put("快乐", FacialExpression.happy);
        FACIAL_EXPRESSION_ALIASES.put("开心", FacialExpression.happy);
        FACIAL_EXPRESSION_ALIASES.put("中性", FacialExpression.neutral);
        FACIAL_EXPRESSION_ALIASES.put("悲伤", FacialExpression.sad);
        FACIAL_EXPRESSION_ALIASES.put("惊讶", FacialExpression.surprise);
        FACIAL_EXPRESSION_ALIASES.put("轻蔑", FacialExpression.disgust);
        // 英文变体
        FACIAL_EXPRESSION_ALIASES.put("surprised", FacialExpression.surprise);
        FACIAL_EXPRESSION_ALIASES.put("disgusted", FacialExpression.disgust);
        FACIAL_EXPRESSION_ALIASES.put("fearful", FacialExpression.fear);
        FACIAL_EXPRESSION_ALIASES.put("afraid", FacialExpression.fear);
        FACIAL_EXPRESSION_ALIASES.put("mad", FacialExpression.angry);
        FACIAL_EXPRESSION_ALIASES.put("anger", FacialExpression.angry);
        FACIAL_EXPRESSION_ALIASES.put("joyful", FacialExpression.happy);
        FACIAL_EXPRESSION_ALIASES.put("joy", FacialExpression.happy);
        FACIAL_EXPRESSION_ALIASES.put("calm", FacialExpression.neutral);
    }

    public static FacialExpression parseFacialExpression(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty()) return null;
        FacialExpression exact = FacialExpression.ofValue(v);
        if (exact != null) return exact;
        FacialExpression alias = FACIAL_EXPRESSION_ALIASES.get(v);
        if (alias != null) return alias;
        return FACIAL_EXPRESSION_ALIASES.get(v.toLowerCase());
    }

    public static String facialExpressionLabel(FacialExpression expr) {
        if (expr == null) return null;
        return FACIAL_EXPRESSION_LABELS.get(expr);
    }

    // ---------- 手势 ----------

    public static final Map<String, String> GESTURE_LABELS = new LinkedHashMap<>();
    static {
        GESTURE_LABELS.put("wave", "挥手");
        GESTURE_LABELS.put("nod", "点头");
        GESTURE_LABELS.put("shake_head", "摇头");
        GESTURE_LABELS.put("thumbs_up", "点赞");
        GESTURE_LABELS.put("heart", "比心");
        GESTURE_LABELS.put("raise_hand", "举手");
        GESTURE_LABELS.put("ok", "OK手势");
        GESTURE_LABELS.put("victory", "比耶");
        GESTURE_LABELS.put("fist", "握拳");
        GESTURE_LABELS.put("point", "指向");
        GESTURE_LABELS.put("open_palm", "张开手掌");
    }

    public static String gestureLabel(String key) {
        if (key == null) return null;
        String k = key.trim();
        if (k.isEmpty()) return null;
        String v = GESTURE_LABELS.get(k);
        if (v != null) return v;
        v = GESTURE_LABELS.get(k.toLowerCase());
        return v != null ? v : k;
    }

    // ---------- 姿态（预留，当前版本固定返回 null） ----------

    public static final Map<String, String> POSTURE_LABELS = new LinkedHashMap<>();
    static {
    }

    public static String postureLabel(String key) {
        if (key == null) return null;
        String k = key.trim();
        if (k.isEmpty()) return null;
        String v = POSTURE_LABELS.get(k);
        if (v != null) return v;
        v = POSTURE_LABELS.get(k.toLowerCase());
        return v != null ? v : k;
    }

    // ---------- 内心独白检测 ----------

    private record Marker(Pattern pattern, int weight) {}

    private static final List<Marker> MONOLOGUE_MARKERS = List.of(
            new Marker(Pattern.compile("长期记忆"), 2),
            new Marker(Pattern.compile("用户.{0,20}(表情|说|长期|可能|悲伤|开心|抚摸)"), 2),
            new Marker(Pattern.compile("我(需要|应该|得)(回应|安慰|安抚|回复|温暖)"), 2),
            new Marker(Pattern.compile("结合.{0,30}(抚摸|表情|语气|悲伤)"), 2),
            new Marker(Pattern.compile("似乎(有点|有些|是)"), 1),
            new Marker(Pattern.compile("不要追问"), 2),
            new Marker(Pattern.compile("\\[感知"), 2),
            new Marker(Pattern.compile("L[1-4][\\s/]"), 2),
            new Marker(Pattern.compile("触发了"), 1),
            new Marker(Pattern.compile("被用户"), 2),
            new Marker(Pattern.compile("分析一下|推测|推断"), 2)
    );

    /** 判断文本是否像模型内心分析，而非对用户说的原话。对应 looks_like_internal_monologue。 */
    public static boolean looksLikeInternalMonologue(String text) {
        String t = text == null ? "" : text.trim();
        if (t.length() < 12) return false;
        int score = 0;
        for (Marker m : MONOLOGUE_MARKERS) {
            if (m.pattern().matcher(t).find()) score += m.weight();
        }
        return score >= 2;
    }

    // ---------- 语音补全 ----------

    public static VoiceProsody voiceForExpression(FacialExpression expr, VoiceProsody voice) {
        Map<FacialExpression, VoiceProsody> defaults = new LinkedHashMap<>();
        defaults.put(FacialExpression.happy, new VoiceProsody("兴奋", "上扬", "正常"));
        defaults.put(FacialExpression.sad, new VoiceProsody("低落", "下沉", "慢"));
        defaults.put(FacialExpression.angry, new VoiceProsody("急躁", "起伏大", "快"));
        defaults.put(FacialExpression.fear, new VoiceProsody("疑问", "起伏大", "快"));
        defaults.put(FacialExpression.surprise, new VoiceProsody("惊讶", "上扬", "快"));
        defaults.put(FacialExpression.disgust, new VoiceProsody("冷淡", "平稳", "正常"));
        defaults.put(FacialExpression.neutral, new VoiceProsody("温柔", "平稳", "正常"));
        VoiceProsody base = defaults.getOrDefault(expr, new VoiceProsody("温柔", "平稳", "正常"));
        if (voice == null) return base;
        return new VoiceProsody(
                truthy(voice.getTone()) ? voice.getTone() : base.getTone(),
                truthy(voice.getIntonation()) ? voice.getIntonation() : base.getIntonation(),
                truthy(voice.getSpeed()) ? voice.getSpeed() : base.getSpeed()
        );
    }

    // ---------- 感知展平 ----------

    public static Map<String, Object> perceptionToDict(PerceptionInput perception) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (perception == null) return data;
        if (perception.getFacialExpression() != null) {
            data.put("facial_expression", facialExpressionLabel(perception.getFacialExpression()));
        }
        VoiceProsody v = perception.getVoice();
        if (v != null) {
            if (truthy(v.getTone())) data.put("tone", v.getTone());
            if (truthy(v.getIntonation())) data.put("intonation", v.getIntonation());
            if (truthy(v.getSpeed())) data.put("speed", v.getSpeed());
        }
        if (truthy(perception.getTouch())) data.put("touch", perception.getTouch());
        if (truthy(perception.getIdentity())) data.put("identity", perception.getIdentity());
        if (perception.getGesture() != null && truthy(perception.getGesture().getType())) {
            data.put("gesture", gestureLabel(perception.getGesture().getType()));
        }
        // posture 预留，不进 LLM
        return data;
    }

    public static final List<String[]> PERCEPTION_LABELS = List.of(
            new String[]{"tone", "语气"},
            new String[]{"intonation", "语调"},
            new String[]{"speed", "语速"},
            new String[]{"touch", "抚摸"},
            new String[]{"gesture", "手势"},
            new String[]{"identity", "身份"},
            new String[]{"facial_expression", "表情"}
    );

    public static final Set<String> VOICE_KEYS = Set.of("tone", "intonation", "speed");

    public static String formatPerceptionDict(Map<String, Object> data) {
        List<String> parts = new ArrayList<>();
        for (String[] pair : PERCEPTION_LABELS) {
            Object raw = data.get(pair[0]);
            String v = raw == null ? null : raw.toString();
            if (v != null) v = v.trim();
            if (truthy(v)) parts.add(pair[1] + ":" + v);
        }
        return String.join(" ", parts);
    }

    // ---------- 从 LLM JSON 构建 RobotOutput ----------

    public static RobotOutput robotOutputFromLlm(Map<String, Object> data, String fallbackText) {
        String text = firstTruthy(asStr(data.get("text")), fallbackText, "");
        text = text == null ? "" : text.trim();
        FacialExpression expr = parseFacialExpression(asStr(data.get("facial_expression")));
        if (expr == null) expr = FacialExpression.neutral;

        VoiceProsody voice = null;
        Object voiceRaw = data.get("voice");
        if (voiceRaw instanceof Map<?, ?> vr) {
            Object tone = vr.get("tone");
            Object inton = vr.get("intonation");
            Object speed = vr.get("speed");
            if (truthy(asStr(tone)) || truthy(asStr(inton)) || truthy(asStr(speed))) {
                voice = new VoiceProsody(asStr(tone), asStr(inton), asStr(speed));
            }
        }
        voice = voiceForExpression(expr, voice);
        RobotState state = parseRobotState(asStr(data.get("robot_state")));

        RobotOutput out = new RobotOutput();
        out.setText(text);
        out.setFacialExpression(expr);
        out.setRobotState(state);
        out.setVoice(voice);
        out.setGesture(null);
        out.setPosture(null);
        return out.finalizeOutput();
    }

    public static RobotOutput robotOutputFromLlm(Map<String, Object> data) {
        return robotOutputFromLlm(data, "");
    }

    // ---------- 共情对齐 ----------

    private static final Map<FacialExpression, FacialExpression> EMPATHY_EXPRESSION = new LinkedHashMap<>();
    static {
        EMPATHY_EXPRESSION.put(FacialExpression.sad, FacialExpression.sad);
        EMPATHY_EXPRESSION.put(FacialExpression.fear, FacialExpression.sad);
        EMPATHY_EXPRESSION.put(FacialExpression.angry, FacialExpression.sad);
    }

    /** 用户有明显负面情绪时，将机器人 neutral 表情纠正为共情（sad）。对应 align_output_to_user_perception。 */
    public static RobotOutput alignOutputToUserPerception(RobotOutput output, FacialExpression userExpr) {
        if (userExpr == null || output.getFacialExpression() != FacialExpression.neutral) {
            return output;
        }
        FacialExpression target = EMPATHY_EXPRESSION.get(userExpr);
        if (target == null) return output;
        output.setFacialExpression(target);
        output.setVoice(new VoiceProsody("温柔", "下沉", "慢"));
        output.setRobotState(robotStateForExpression(target));
        return output.finalizeOutput();
    }

    // ---------- 工具 ----------

    private static boolean truthy(String s) {
        return s != null && !s.isEmpty();
    }

    private static String asStr(Object o) {
        if (o == null) return null;
        if (o instanceof String s) return s;
        return o.toString();
    }

    private static String firstTruthy(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    // ---------- 输出格式指令（逐字照搬 schemas.py REPLY_JSON_INSTRUCTION） ----------

    public static final String REPLY_JSON_INSTRUCTION = """

【输出格式 — 最高优先级，每次必须遵守】
你只输出一个 JSON 对象，不要 markdown、不要 ```、不要 JSON 前后的任何说明。
第一个字符必须是 {，最后一个字符必须是 }。

固定结构：
{"text":"对用户说的话","facial_expression":"sad","robot_state":"sleepy","voice":{"tone":"温柔","intonation":"下沉","speed":"慢"},"gesture":null,"posture":null}

完整示例（照着这个格式回，只改 text/表情/robot_state/voice 内容）：

用户输入：[非语言信号 表情:悲伤]
你的输出：{"text":"我在这儿呢，想安静待着我就陪着。","facial_expression":"sad","robot_state":"sleepy","voice":{"tone":"温柔","intonation":"下沉","speed":"慢"},"gesture":null,"posture":null}

用户输入：[感知 表情:悲伤] 你好
你的输出：{"text":"你好呀，我在这儿陪着你。","facial_expression":"sad","robot_state":"sleepy","voice":{"tone":"温柔","intonation":"下沉","speed":"慢"},"gesture":null,"posture":null}

用户输入：今天天气不错
你的输出：{"text":"是呀，要不要出去走走？","facial_expression":"happy","robot_state":"happy","voice":{"tone":"兴奋","intonation":"上扬","speed":"正常"},"gesture":null,"posture":null}

字段说明：
- text: 你对用户亲口说的话（1～3 句，用「你/我」直接对话）。禁止写思考过程、禁止第三人称分析用户、禁止提长期记忆或感知标签。
- facial_expression: 机器人自己的表情，取值 angry|disgust|fear|happy|neutral|sad|surprise
- robot_state: 机器人此刻的互动动作状态，**只能取虚拟形象状态机 9 态之一**：idle(待机)|gazing(注视)|listening(聆听)|thinking(思考)|happy(高兴)|confused(困惑)|sleepy(困倦)|sleeping(睡眠)|waking(苏醒)。不要使用此列表以外的任何值。
- voice.tone: 温柔|平静|急躁|兴奋|低落|撒娇|疑问|冷淡
- voice.intonation: 平稳|上扬|下沉|起伏大
- voice.speed: 慢|正常|快|极快
- gesture/posture: 固定 null

表情与状态规则：
- 用户悲伤/恐惧/恼怒且你在安慰：facial_expression 用 sad，robot_state 用 sleepy，voice 温柔+下沉+慢
- 用户开心 / 你也开心：facial_expression 用 happy，robot_state 用 happy
- 平淡闲聊：facial_expression 用 neutral，robot_state 用 idle 或 gazing
- 没听懂 / 意外：robot_state 用 confused
- robot_state 必须与语气一致，且只能是上面 9 个值之一""";
}
