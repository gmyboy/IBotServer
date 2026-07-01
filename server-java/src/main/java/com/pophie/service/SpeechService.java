package com.pophie.service;

import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation;
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality;
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam;
import com.alibaba.dashscope.audio.omni.OmniRealtimeTranscriptionParam;
import com.google.gson.JsonObject;
import com.pophie.config.RuntimeConfigService;
import com.pophie.schema.AudioPayload;
import com.pophie.schema.SttResult;
import com.pophie.schema.VoiceProsody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语音模块：STT + TTS（阿里云 DashScope）。逐函数对应 speech.py。
 *
 * 说明：纯逻辑（音色表/情感映射/sanitize/rate-pitch/format/魔数判定/重试/启用判断）与 Python 完全一致；
 * DashScope SDK 调用集中在 runTtsWs / transcribeDashScope 两处，是计划中标注的 SDK 版本核对点。
 */
@Service
public class SpeechService {

    private static final Logger log = LoggerFactory.getLogger("pophie.speech");

    public static final String DASHSCOPE_TTS_WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";
    /** Qwen3-ASR-Realtime WebSocket（与 TTS 的 /inference 端点不同） */
    public static final String DASHSCOPE_STT_WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime";
    public static final String DEFAULT_VOICE_ID = "gentle_female";

    // voice_id -> [DashScope API voice, 显示名]
    public static final Map<String, String[]> TTS_VOICES = new LinkedHashMap<>();
    static {
        TTS_VOICES.put("sunny_boy", new String[]{"longanyang", "阳光大男孩"});
        TTS_VOICES.put("energetic_girl", new String[]{"longanhuan", "欢脱元气女"});
        TTS_VOICES.put("gentle_female", new String[]{"longxiaochun_v3", "知性女声"});
        TTS_VOICES.put("lively_female", new String[]{"longxiaoxia_v3", "沉稳女声"});
        TTS_VOICES.put("natural_female", new String[]{"longwan_v3", "柔声女声"});
        TTS_VOICES.put("warm_female", new String[]{"longyue_v3", "温暖磁性女"});
        TTS_VOICES.put("elegant_female", new String[]{"longanwen_v3", "优雅知性女"});
        TTS_VOICES.put("cozy_female", new String[]{"longanqin_v3", "亲和活泼女"});
        TTS_VOICES.put("graceful_female", new String[]{"longanya_v3", "高雅气质女"});
        TTS_VOICES.put("clever_female", new String[]{"longanling_v3", "思维灵动女"});
        TTS_VOICES.put("soft_female", new String[]{"longanrou_v3", "温柔闺蜜女"});
        TTS_VOICES.put("sweet_female", new String[]{"longfeifei_v3", "甜美娇气女"});
        TTS_VOICES.put("youthful_female", new String[]{"longhua_v3", "元气甜美女"});
        TTS_VOICES.put("gentle_male", new String[]{"longanyun_v3", "居家暖男"});
        TTS_VOICES.put("calm_male", new String[]{"longshu_v3", "沉稳男声"});
        TTS_VOICES.put("sunny_male", new String[]{"longshuo_v3", "干练男声"});
        TTS_VOICES.put("deep_male", new String[]{"longfei_v3", "磁性男声"});
        TTS_VOICES.put("clear_male", new String[]{"longanlang_v3", "清爽利落男"});
        TTS_VOICES.put("wise_male", new String[]{"longanzhi_v3", "睿智男声"});
        TTS_VOICES.put("warm_male", new String[]{"longze_v3", "温暖元气男"});
        TTS_VOICES.put("magnetic_male", new String[]{"longtian_v3", "磁性理智男"});
        TTS_VOICES.put("refreshing_male", new String[]{"longanshuo_v3", "干净清爽男"});
        TTS_VOICES.put("yumi_female", new String[]{"longyumi_v3", "正经青年女"});
        TTS_VOICES.put("child_girl", new String[]{"longhuhu_v3", "童声女童"});
        TTS_VOICES.put("child_boy", new String[]{"longniuniu_v3", "童声男童"});
        TTS_VOICES.put("cartoon_male", new String[]{"longjielidou_v3", "顽皮童声"});
        TTS_VOICES.put("child_bubble", new String[]{"longpaopao_v3", "泡泡童声"});
        TTS_VOICES.put("robot_voice", new String[]{"longjiqi_v3", "呆萌机器人"});
        TTS_VOICES.put("monkey_voice", new String[]{"longhouge_v3", "经典猴哥"});
        TTS_VOICES.put("cute_female", new String[]{"longmiao_v3", "抑扬女声"});
        TTS_VOICES.put("storyteller_male", new String[]{"longsanshu_v3", "沉稳质感男"});
        TTS_VOICES.put("healing_female", new String[]{"longyuan_v3", "温暖治愈女"});
        TTS_VOICES.put("dialect_northeast", new String[]{"longlaotie_v3", "东北男声"});
        TTS_VOICES.put("dialect_shaanxi", new String[]{"longshange_v3", "陕西男声"});
        TTS_VOICES.put("dialect_cantonese_m", new String[]{"longanyue_v3", "粤语男声"});
        TTS_VOICES.put("dialect_cantonese_f", new String[]{"longjiaxin_v3", "粤语女声"});
        TTS_VOICES.put("dialect_minnan", new String[]{"longanmin_v3", "闽南女声"});
        TTS_VOICES.put("dialect_taiwan", new String[]{"longantai_v3", "台湾女声"});
        TTS_VOICES.put("professional_female", new String[]{"loongbella_v3", "干练女声"});
    }

    // 仅下列 DashScope 音色支持 instruction 参数
    public static final Set<String> TTS_INSTRUCT_VOICES = Set.of("longanyang", "longanhuan", "longhuhu_v3");

    // Qwen3-ASR / SenseVoice 情感标签 → VoiceProsody.tone
    private static final Map<String, String> EMOTION_TO_TONE = new LinkedHashMap<>();
    static {
        EMOTION_TO_TONE.put("HAPPY", "兴奋"); EMOTION_TO_TONE.put("SAD", "低落");
        EMOTION_TO_TONE.put("ANGRY", "急躁"); EMOTION_TO_TONE.put("NEUTRAL", "平静");
        EMOTION_TO_TONE.put("FEARFUL", "疑问"); EMOTION_TO_TONE.put("DISGUSTED", "冷淡");
        EMOTION_TO_TONE.put("SURPRISED", "惊讶");
        EMOTION_TO_TONE.put("happy", "兴奋"); EMOTION_TO_TONE.put("sad", "低落");
        EMOTION_TO_TONE.put("angry", "急躁"); EMOTION_TO_TONE.put("neutral", "平静");
        EMOTION_TO_TONE.put("fearful", "疑问"); EMOTION_TO_TONE.put("disgusted", "冷淡");
        EMOTION_TO_TONE.put("surprised", "惊讶");
        EMOTION_TO_TONE.put("开心", "兴奋"); EMOTION_TO_TONE.put("悲伤", "低落");
        EMOTION_TO_TONE.put("愤怒", "急躁"); EMOTION_TO_TONE.put("恼怒", "急躁");
        EMOTION_TO_TONE.put("中性", "平静"); EMOTION_TO_TONE.put("恐惧", "疑问");
        EMOTION_TO_TONE.put("厌恶", "冷淡"); EMOTION_TO_TONE.put("惊讶", "惊讶");
    }

    // VoiceProsody.tone → CosyVoice Instruct 情感值
    private static final Map<String, String> TONE_TO_COSYVOICE_EMOTION = new LinkedHashMap<>();
    static {
        TONE_TO_COSYVOICE_EMOTION.put("兴奋", "happy");
        TONE_TO_COSYVOICE_EMOTION.put("惊讶", "surprised");
        TONE_TO_COSYVOICE_EMOTION.put("低落", "sad");
        TONE_TO_COSYVOICE_EMOTION.put("急躁", "angry");
        TONE_TO_COSYVOICE_EMOTION.put("平静", "neutral");
        TONE_TO_COSYVOICE_EMOTION.put("温柔", "neutral");
        TONE_TO_COSYVOICE_EMOTION.put("疑问", "fearful");
        TONE_TO_COSYVOICE_EMOTION.put("冷淡", "disgusted");
    }

    private static final Pattern EMO_TAG = Pattern.compile("<\\|([A-Za-z_]+)\\|>");
    private static final Pattern EMO_TAG_ALL = Pattern.compile("<\\|[^|]+\\|>");
    private static final Pattern EMO_TAIL = Pattern.compile("\\|([A-Z]+)\\|$");
    private static final Pattern CODE_FENCE = Pattern.compile("```[\\s\\S]*?```");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern EMOJI = Pattern.compile("[\\x{10000}-\\x{10FFFF}]");
    private static final Pattern WS = Pattern.compile("\\s+");

    private final RuntimeConfigService cfg;

    public SpeechService(RuntimeConfigService cfg) {
        this.cfg = cfg;
    }

    private Map<String, Object> speechCfg() { return cfg.speech(); }
    private Map<String, Object> sttCfg() { return RuntimeConfigService.sub(speechCfg(), "stt"); }
    private Map<String, Object> ttsCfg() { return RuntimeConfigService.sub(speechCfg(), "tts"); }

    /** 供 SttRealtimeSession 读取 STT 配置。 */
    Map<String, Object> sttCfgInternal() { return sttCfg(); }

    // ---------- 元信息 ----------

    public String sttEngine() { return "dashscope"; }
    public String ttsEngine() { return "dashscope-realtime"; }

    public String dashscopeApiKey() {
        String stt = RuntimeConfigService.str(sttCfg(), "api_key", "").trim();
        String tts = RuntimeConfigService.str(ttsCfg(), "api_key", "").trim();
        if (!stt.isEmpty()) return stt;
        if (!tts.isEmpty()) return tts;
        String env = System.getenv("DASHSCOPE_API_KEY");
        return env == null ? "" : env.trim();
    }

    public boolean isEnabled() {
        if (!RuntimeConfigService.bool(speechCfg(), "enabled", false)) return false;
        if (dashscopeApiKey().isEmpty()) {
            log.warn("speech.enabled=true 但未配置 DashScope API Key "
                    + "(speech.tts.api_key / speech.stt.api_key / DASHSCOPE_API_KEY)");
            return false;
        }
        return true;
    }

    public List<Map<String, Object>> listTtsVoices() {
        String def = defaultVoiceId();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, String[]> e : TTS_VOICES.entrySet()) {
            String vid = e.getKey();
            String apiVoice = e.getValue()[0];
            String label = e.getValue()[1];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", vid);
            m.put("label", label);
            m.put("default", vid.equals(def));
            m.put("instruct", TTS_INSTRUCT_VOICES.contains(apiVoice));
            out.add(m);
        }
        return out;
    }

    public String defaultVoiceId() {
        String cfgId = RuntimeConfigService.str(ttsCfg(), "default_voice", null);
        if (cfgId != null && TTS_VOICES.containsKey(cfgId)) return cfgId;
        return DEFAULT_VOICE_ID;
    }

    public String resolveVoiceId(String voiceId) {
        if (voiceId != null && TTS_VOICES.containsKey(voiceId)) return voiceId;
        return defaultVoiceId();
    }

    private String dashscopeVoice(String voiceId) {
        return TTS_VOICES.get(resolveVoiceId(voiceId))[0];
    }

    public String ttsOutputFormat() {
        return RuntimeConfigService.str(ttsCfg(), "format", "mp3").toLowerCase();
    }

    public String ttsStreamFormat() {
        return RuntimeConfigService.str(ttsCfg(), "stream_format", "pcm").toLowerCase();
    }

    public int ttsSampleRate() {
        return RuntimeConfigService.integer(ttsCfg(), "sample_rate", 22050);
    }

    // ---------- STT 纯逻辑 ----------

    private byte[] decodeAudio(AudioPayload audio) {
        if (!"base64".equals(audio.getEncoding())) {
            throw new IllegalArgumentException("不支持的音频编码: " + audio.getEncoding());
        }
        return Base64.getDecoder().decode(audio.getData());
    }

    /** WAV → 16-bit PCM（单声道），返回 [pcm, sampleRate]。对应 _wav_to_pcm16。 */
    private Object[] wavToPcm16(byte[] wavBytes) {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wavBytes))) {
            AudioFormat fmt = ais.getFormat();
            int channels = fmt.getChannels();
            int sampleRate = (int) fmt.getSampleRate();
            int sampleSizeBytes = fmt.getSampleSizeInBits() / 8;
            if (channels != 1) throw new IllegalArgumentException("STT 需要单声道音频");
            if (sampleSizeBytes != 2) throw new IllegalArgumentException("STT 需要 16-bit PCM WAV");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = ais.read(buf)) > 0) bos.write(buf, 0, n);
            return new Object[]{bos.toByteArray(), sampleRate};
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /** 解析 SenseVoice 风格情感标签。对应 _strip_emotion_tags，返回 [clean, emotion]。 */
    String[] stripEmotionTags(String text) {
        String raw = text == null ? "" : text;
        String emotion = null;
        Matcher m = EMO_TAG.matcher(raw);
        if (m.find()) emotion = m.group(1);
        String clean = EMO_TAG_ALL.matcher(raw).replaceAll("").trim();
        Matcher tail = EMO_TAIL.matcher(clean);
        if (tail.find()) {
            if (emotion == null) emotion = tail.group(1);
            clean = clean.substring(0, tail.start()).trim();
        }
        return new String[]{clean, emotion};
    }

    VoiceProsody voiceFromEmotion(String emotion) {
        if (emotion == null) return null;
        String tone = EMOTION_TO_TONE.get(emotion);
        if (tone == null) tone = EMOTION_TO_TONE.get(emotion.toUpperCase());
        if (tone == null) tone = EMOTION_TO_TONE.get(emotion.toLowerCase());
        if (tone == null) return null;
        return new VoiceProsody(tone, "平稳", "正常");
    }

    public SttResult transcribe(AudioPayload audio) {
        if (!isEnabled()) throw new RuntimeException("语音功能未启用 (speech.enabled=false)");
        byte[] wavBytes = decodeAudio(audio);
        return transcribeDashscope(wavBytes);
    }

    // ---------- TTS 纯逻辑 ----------

    String buildInstruct(VoiceProsody voice) {
        VoiceProsody v = voice == null ? new VoiceProsody() : voice;
        String tone = v.getTone() == null ? "平静" : v.getTone();
        String emotion = TONE_TO_COSYVOICE_EMOTION.getOrDefault(tone, "neutral");
        return "你说话的情感是" + emotion + "。";
    }

    String sanitizeTtsText(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return "";
        t = CODE_FENCE.matcher(t).replaceAll("");
        t = INLINE_CODE.matcher(t).replaceAll("$1");
        t = EMOJI.matcher(t).replaceAll("");
        t = WS.matcher(t).replaceAll(" ").trim();
        if (t.length() > 800) t = t.substring(0, 800);
        return t;
    }

    double[] ttsRatePitch(VoiceProsody voice) {
        Map<String, Double> rateMap = Map.of("慢", 0.85, "正常", 1.0, "快", 1.15, "极快", 1.3);
        Map<String, Double> pitchMap = Map.of("下沉", 0.9, "平稳", 1.0, "上扬", 1.1, "起伏大", 1.15);
        String spd = voice != null && voice.getSpeed() != null ? voice.getSpeed() : "正常";
        String pit = voice != null && voice.getIntonation() != null ? voice.getIntonation() : "平稳";
        return new double[]{rateMap.getOrDefault(spd, 1.0), pitchMap.getOrDefault(pit, 1.0)};
    }

    /** idle 与 max_total=base+len*per_char，cap=max_timeout。对应 _tts_wait_limits。 */
    double[] ttsWaitLimits(String text) {
        Map<String, Object> tts = ttsCfg();
        double base = RuntimeConfigService.dbl(tts, "timeout", 30);
        double perChar = RuntimeConfigService.dbl(tts, "timeout_per_char", 0.2);
        double idle = RuntimeConfigService.dbl(tts, "idle_timeout", base);
        double maxTotal = base + text.length() * perChar;
        double cap = RuntimeConfigService.dbl(tts, "max_timeout", 300);
        return new double[]{idle, Math.min(maxTotal, cap)};
    }

    String ttsInstruction(String timbre, VoiceProsody voice) {
        if (TTS_INSTRUCT_VOICES.contains(timbre) && voice != null
                && (truthy(voice.getTone()) || truthy(voice.getIntonation()) || truthy(voice.getSpeed()))) {
            return buildInstruct(voice);
        }
        return null;
    }

    public AudioPayload synthesizePayload(String text, VoiceProsody voice, String voiceId) {
        Object[] res = synthesize(text, voice, voiceId);
        byte[] audioBytes = (byte[]) res[0];
        int sr = (int) res[1];
        if (audioBytes.length == 0) throw new RuntimeException("TTS 合成结果为空");
        String fmt = ttsOutputFormat();
        if (fmt.equals("pcm")) {
            fmt = "pcm";
        } else if ((audioBytes.length >= 3 && audioBytes[0] == 'I' && audioBytes[1] == 'D' && audioBytes[2] == '3')
                || (audioBytes.length > 2 && (audioBytes[0] & 0xFF) == 0xFF)) {
            fmt = "mp3";
        } else if (audioBytes.length >= 4 && audioBytes[0] == 'R' && audioBytes[1] == 'I'
                && audioBytes[2] == 'F' && audioBytes[3] == 'F') {
            fmt = "wav";
        }
        return new AudioPayload(fmt, "base64", sr, Base64.getEncoder().encodeToString(audioBytes));
    }

    /** 返回 [byte[] audio, int sampleRate]。对应 synthesize。 */
    public Object[] synthesize(String text, VoiceProsody voice, String voiceId) {
        if (!isEnabled()) throw new RuntimeException("语音功能未启用 (speech.enabled=false)");
        String clean = sanitizeTtsText(text);
        int sampleRate = ttsSampleRate();
        if (clean.isEmpty()) return new Object[]{new byte[0], sampleRate};
        String fmt = ttsOutputFormat();
        List<byte[]> chunks = new ArrayList<>();
        runTtsWs(clean, voice, voiceId, fmt, sampleRate, chunks::add, null);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (byte[] c : chunks) bos.write(c, 0, c.length);
        return new Object[]{bos.toByteArray(), sampleRate};
    }

    /** WebSocket 流式 TTS，逐块回调音频字节。对应 iter_tts_chunks。 */
    public void iterTtsChunks(String text, VoiceProsody voice, String voiceId,
                              Map<String, Object> metrics, Consumer<byte[]> onChunk) {
        if (!isEnabled()) throw new RuntimeException("语音功能未启用 (speech.enabled=false)");
        String clean = sanitizeTtsText(text);
        if (clean.isEmpty()) return;
        String fmt = ttsStreamFormat();
        int sampleRate = ttsSampleRate();
        runTtsWs(clean, voice, voiceId, fmt, sampleRate, onChunk, metrics);
    }

    // ============ DashScope SDK 调用（计划标注的版本核对点） ============

    /**
     * CosyVoice WebSocket 流式合成。重试逻辑与 rate/pitch/format/instruction 传参对应 _run_tts_ws。
     */
    @SuppressWarnings("unchecked")
    private void runTtsWs(String text, VoiceProsody voice, String voiceId,
                          String outputFormat, int sampleRate,
                          Consumer<byte[]> onChunk, Map<String, Object> metrics) {
        Map<String, Object> tts = ttsCfg();
        String apiKey = dashscopeApiKey();
        if (apiKey.isEmpty()) {
            throw new RuntimeException("DashScope TTS 未配置 api_key（或环境变量 DASHSCOPE_API_KEY）");
        }
        String model = RuntimeConfigService.str(tts, "model", "cosyvoice-v3-flash");
        double[] waits = ttsWaitLimits(text);
        int maxRetries = RuntimeConfigService.integer(tts, "max_retries", 3);
        double retryDelay = RuntimeConfigService.dbl(tts, "retry_delay_sec", 1.0);
        String vid = resolveVoiceId(voiceId);
        String timbre = dashscopeVoice(voiceId);
        double[] rp = ttsRatePitch(voice);
        com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat audioFormat =
                resolveAudioFormat(outputFormat, sampleRate);
        String instruction = ttsInstruction(timbre, voice);

        RuntimeException lastErr = null;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                long first = doTtsCall(apiKey, model, timbre, audioFormat, rp[0], rp[1],
                        instruction, text, onChunk);
                if (metrics != null) metrics.put("first_packet_ms", first);
                log.info("[speech] TTS ws ok voice_id={} len={} model={} fmt={}",
                        vid, text.length(), model, outputFormat);
                return;
            } catch (Exception e) {
                lastErr = new RuntimeException(e.getMessage(), e);
                log.warn("[speech] TTS ws 失败 attempt={}/{}: {}", attempt + 1, maxRetries, e.getMessage());
                if (attempt + 1 < maxRetries) {
                    try { Thread.sleep((long) (retryDelay * 1000)); } catch (InterruptedException ignored) {}
                }
            }
        }
        throw new RuntimeException("TTS 合成失败: " + (lastErr == null ? "" : lastErr.getMessage()), lastErr);
    }

    private long doTtsCall(String apiKey, String model, String timbre,
                           com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat audioFormat,
                           double rate, double pitch, String instruction, String text,
                           Consumer<byte[]> onChunk) {
        com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam param =
                com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam.builder()
                        .apiKey(apiKey)
                        .model(model)
                        .voice(timbre)
                        .format(audioFormat)
                        .speechRate((float) rate)
                        .pitchRate((float) pitch)
                        .build();

        AtomicReference<Throwable> err = new AtomicReference<>();
        com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer synthesizer =
                new com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer(param,
                        new com.alibaba.dashscope.common.ResultCallback<com.alibaba.dashscope.audio.tts.SpeechSynthesisResult>() {
                            @Override
                            public void onEvent(com.alibaba.dashscope.audio.tts.SpeechSynthesisResult r) {
                                ByteBuffer f = r.getAudioFrame();
                                if (f != null && f.remaining() > 0) {
                                    byte[] bytes = new byte[f.remaining()];
                                    f.get(bytes);
                                    onChunk.accept(bytes);
                                }
                            }

                            @Override
                            public void onComplete() {}

                            @Override
                            public void onError(Exception e) {
                                err.set(e);
                            }
                        });
        synthesizer.call(text);
        if (err.get() != null) {
            throw new RuntimeException(err.get().getMessage(), err.get());
        }
        try {
            return synthesizer.getFirstPackageDelay();
        } catch (Exception e) {
            return -1;
        }
    }

    private com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat resolveAudioFormat(String fmtIn, int sampleRate) {
        String fmt = (fmtIn == null ? "mp3" : fmtIn).toLowerCase();
        String[] candidates = {
                fmt.toUpperCase() + "_" + sampleRate + "HZ_MONO_256KBPS",
                fmt.toUpperCase() + "_" + sampleRate + "HZ_MONO_16BIT",
                fmt.toUpperCase() + "_" + sampleRate + "HZ_MONO",
        };
        for (String name : candidates) {
            try {
                return com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat.valueOf(name);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (fmt.equals("pcm")) {
            return com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat.PCM_22050HZ_MONO_16BIT;
        }
        return com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat.MP3_22050HZ_MONO_256KBPS;
    }

    /**
     * Qwen3-ASR 实时识别（OmniRealtime WebSocket）。对应 Python _transcribe_dashscope。
     */
    private SttResult transcribeDashscope(byte[] wavBytes) {
        Map<String, Object> stt = sttCfg();
        String apiKey = dashscopeApiKey();
        if (apiKey.isEmpty()) throw new RuntimeException("DashScope STT 未配置 API Key");

        Object[] pcmRes = wavToPcm16(wavBytes);
        byte[] pcm = (byte[]) pcmRes[0];
        int sampleRate = (int) pcmRes[1];
        int expectedSr = RuntimeConfigService.integer(stt, "sample_rate", 16000);
        if (sampleRate != expectedSr) {
            throw new IllegalArgumentException("STT 需要 " + expectedSr + "Hz 采样率，当前为 " + sampleRate + "Hz");
        }

        String model = RuntimeConfigService.str(stt, "model", "qwen3-asr-flash-realtime");
        int chunkBytes = RuntimeConfigService.integer(stt, "chunk_bytes", 3200);
        int maxRetries = RuntimeConfigService.integer(stt, "max_retries", 3);
        double retryDelay = RuntimeConfigService.dbl(stt, "retry_delay_sec", 1.0);

        RuntimeException lastErr = null;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            long t0 = System.currentTimeMillis();
            try {
                SttResult result = doOmniRealtimeRecognition(apiKey, model, sampleRate, pcm, chunkBytes, stt);
                log.info("[speech] STT ok in {}ms text={} model={}",
                        System.currentTimeMillis() - t0, result.getText(), model);
                return result;
            } catch (Exception e) {
                lastErr = new RuntimeException(e.getMessage(), e);
                log.warn("[speech] STT 失败 attempt={}/{}: {}", attempt + 1, maxRetries, e.getMessage());
                if (attempt + 1 < maxRetries) {
                    try { Thread.sleep((long) (retryDelay * 1000)); } catch (InterruptedException ignored) {}
                }
            }
        }
        throw new RuntimeException("STT 识别失败: " + (lastErr == null ? "" : lastErr.getMessage()), lastErr);
    }

    private SttResult doOmniRealtimeRecognition(String apiKey, String model, int sampleRate,
                                              byte[] pcm, int chunkBytes, Map<String, Object> stt)
            throws Exception {
        String language = RuntimeConfigService.str(stt, "language", "zh");
        String wsUrl = RuntimeConfigService.str(stt, "base_url", DASHSCOPE_STT_WS_URL).trim();
        if (wsUrl.isEmpty()) wsUrl = DASHSCOPE_STT_WS_URL;
        double connectDelay = RuntimeConfigService.dbl(stt, "connect_delay_sec", 0.1);
        int timeoutSec = RuntimeConfigService.integer(stt, "timeout", 30);

        AtomicReference<String> text = new AtomicReference<>("");
        AtomicReference<String> emotion = new AtomicReference<>(null);
        AtomicReference<String> error = new AtomicReference<>(null);
        CountDownLatch done = new CountDownLatch(1);

        OmniRealtimeParam param = OmniRealtimeParam.builder()
                .model(model)
                .url(wsUrl)
                .apikey(apiKey)
                .header("OpenAI-Beta", "realtime=v1")
                .build();

        OmniRealtimeConversation conversation = new OmniRealtimeConversation(param, new OmniRealtimeCallback() {
            @Override
            public void onOpen() {}

            @Override
            public void onEvent(JsonObject message) {
                if (!message.has("type")) return;
                String type = message.get("type").getAsString();
                switch (type) {
                    case "conversation.item.input_audio_transcription.completed" -> {
                        if (message.has("transcript")) {
                            text.set(message.get("transcript").getAsString().trim());
                        }
                        if (message.has("emotion") && !message.get("emotion").isJsonNull()) {
                            emotion.set(message.get("emotion").getAsString());
                        }
                        done.countDown();
                    }
                    case "conversation.item.input_audio_transcription.text" -> {
                        StringBuilder partial = new StringBuilder();
                        if (message.has("text")) partial.append(message.get("text").getAsString());
                        if (message.has("stash")) partial.append(message.get("stash").getAsString());
                        if (!partial.toString().trim().isEmpty()) {
                            text.set(partial.toString().trim());
                        }
                        if (message.has("emotion") && !message.get("emotion").isJsonNull()) {
                            emotion.set(message.get("emotion").getAsString());
                        }
                    }
                    case "error" -> {
                        error.set(message.has("message")
                                ? message.get("message").getAsString()
                                : message.toString());
                        done.countDown();
                    }
                    default -> { }
                }
            }

            @Override
            public void onClose(int code, String reason) {
                done.countDown();
            }
        });

        try {
            conversation.connect();

            OmniRealtimeTranscriptionParam transcriptionParam = new OmniRealtimeTranscriptionParam();
            transcriptionParam.setLanguage(language);
            transcriptionParam.setInputSampleRate(sampleRate);
            transcriptionParam.setInputAudioFormat("pcm");

            OmniRealtimeConfig config = OmniRealtimeConfig.builder()
                    .modalities(Collections.singletonList(OmniRealtimeModality.TEXT))
                    .enableInputAudioTranscription(true)
                    .enableTurnDetection(false)
                    .transcriptionConfig(transcriptionParam)
                    .build();
            conversation.updateSession(config);

            if (connectDelay > 0) {
                Thread.sleep((long) (connectDelay * 1000));
            }

            for (int i = 0; i < pcm.length; i += chunkBytes) {
                int end = Math.min(i + chunkBytes, pcm.length);
                conversation.appendAudio(Base64.getEncoder().encodeToString(
                        Arrays.copyOfRange(pcm, i, end)));
            }
            conversation.commit();
            conversation.endSession(timeoutSec);

            if (!done.await(timeoutSec * 1000L + 5000L, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("STT 识别超时");
            }
            if (error.get() != null) {
                throw new RuntimeException(error.get());
            }

            String[] parsed = stripEmotionTags(text.get());
            String txt = parsed[0];
            String taggedEmotion = parsed[1];
            String finalEmotion = emotion.get() != null ? emotion.get() : taggedEmotion;
            return new SttResult(txt, voiceFromEmotion(finalEmotion));
        } finally {
            try {
                conversation.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean truthy(String s) {
        return s != null && !s.isEmpty();
    }
}
