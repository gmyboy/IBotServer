package com.pophie.service;

import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation;
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality;
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam;
import com.alibaba.dashscope.audio.omni.OmniRealtimeTranscriptionParam;
import com.google.gson.JsonObject;
import com.pophie.config.RuntimeConfigService;
import com.pophie.schema.SttResult;
import com.pophie.schema.VoiceProsody;
import com.pophie.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * DashScope OmniRealtime STT：边说边推 partial，commit 后推 final。与声纹/主人判定无关。
 */
public final class SttRealtimeSession {

    private static final Logger log = LoggerFactory.getLogger("pophie.speech");

    private final SpeechService speech;
    private final Consumer<String> emit;
    private final Object lock = new Object();

    private OmniRealtimeConversation conversation;
    private volatile boolean utteranceOpen;
    private volatile boolean closed;

    private AtomicReference<String> text;
    private AtomicReference<String> emotion;
    private AtomicReference<String> error;
    private CountDownLatch utteranceDone;

    public SttRealtimeSession(SpeechService speech, Consumer<String> emit) {
        this.speech = speech;
        this.emit = emit;
    }

    public void start(int sampleRate) throws Exception {
        synchronized (lock) {
            if (closed) throw new IllegalStateException("session closed");
            if (!speech.isEnabled()) throw new IllegalStateException("语音功能未启用");
            closeConversation();
            openConversation(sampleRate);
            utteranceOpen = true;
            emitEvent("ready", Map.of("sample_rate", sampleRate));
        }
    }

    public void appendPcm(byte[] pcm) throws Exception {
        if (pcm == null || pcm.length == 0) return;
        synchronized (lock) {
            if (closed) return;
            if (!utteranceOpen || conversation == null) {
                throw new IllegalStateException("请先发送 start");
            }
            conversation.appendAudio(Base64.getEncoder().encodeToString(pcm));
        }
    }

    public void commit() {
        synchronized (lock) {
            if (closed || !utteranceOpen || conversation == null) return;
            utteranceOpen = false;
            Map<String, Object> stt = speech.sttCfgInternal();
            int timeoutSec = RuntimeConfigService.integer(stt, "timeout", 30);
            try {
                conversation.commit();
                conversation.endSession(timeoutSec);
            } catch (Exception e) {
                emitError(e.getMessage());
                closeConversation();
                return;
            }
            CountDownLatch done = utteranceDone;
            OmniRealtimeConversation conv = conversation;
            Thread waiter = new Thread(() -> waitFinal(done, conv, timeoutSec), "stt-realtime-wait");
            waiter.setDaemon(true);
            waiter.start();
        }
    }

    public void close() {
        synchronized (lock) {
            closed = true;
            closeConversation();
        }
    }

    private void waitFinal(CountDownLatch done, OmniRealtimeConversation conv, int timeoutSec) {
        try {
            if (!done.await(timeoutSec * 1000L + 5000L, TimeUnit.MILLISECONDS)) {
                emitError("STT 识别超时");
                return;
            }
            if (error.get() != null) {
                emitError(error.get());
                return;
            }
            String[] parsed = speech.stripEmotionTags(text.get());
            String finalEmotion = emotion.get() != null ? emotion.get() : parsed[1];
            VoiceProsody voice = speech.voiceFromEmotion(finalEmotion);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("text", parsed[0]);
            if (voice != null) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("tone", voice.getTone());
                v.put("intonation", voice.getIntonation());
                v.put("speed", voice.getSpeed());
                payload.put("voice", v);
            }
            emitEvent("final", payload);
            log.debug("[speech] STT stream final text={}", parsed[0]);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitError("STT 等待中断");
        } finally {
            synchronized (lock) {
                if (conversation == conv) {
                    closeConversation();
                }
            }
        }
    }

    private void openConversation(int sampleRate) throws Exception {
        Map<String, Object> stt = speech.sttCfgInternal();
        String apiKey = speech.dashscopeApiKey();
        if (apiKey.isEmpty()) throw new IllegalStateException("DashScope STT 未配置 API Key");

        int expectedSr = RuntimeConfigService.integer(stt, "sample_rate", 16000);
        if (sampleRate != expectedSr) {
            throw new IllegalArgumentException("STT 需要 " + expectedSr + "Hz，当前 " + sampleRate);
        }

        String model = RuntimeConfigService.str(stt, "model", "qwen3-asr-flash-realtime");
        String language = RuntimeConfigService.str(stt, "language", "zh");
        String wsUrl = RuntimeConfigService.str(stt, "base_url", SpeechService.DASHSCOPE_STT_WS_URL).trim();
        if (wsUrl.isEmpty()) wsUrl = SpeechService.DASHSCOPE_STT_WS_URL;
        double connectDelay = RuntimeConfigService.dbl(stt, "connect_delay_sec", 0.0);

        text = new AtomicReference<>("");
        emotion = new AtomicReference<>(null);
        error = new AtomicReference<>(null);
        utteranceDone = new CountDownLatch(1);

        OmniRealtimeParam param = OmniRealtimeParam.builder()
                .model(model)
                .url(wsUrl)
                .apikey(apiKey)
                .header("OpenAI-Beta", "realtime=v1")
                .build();

        conversation = new OmniRealtimeConversation(param, new OmniRealtimeCallback() {
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
                        utteranceDone.countDown();
                    }
                    case "conversation.item.input_audio_transcription.text" -> {
                        StringBuilder partial = new StringBuilder();
                        if (message.has("text")) partial.append(message.get("text").getAsString());
                        if (message.has("stash")) partial.append(message.get("stash").getAsString());
                        String p = partial.toString().trim();
                        if (!p.isEmpty()) {
                            text.set(p);
                            emitEvent("partial", Map.of("text", p));
                        }
                        if (message.has("emotion") && !message.get("emotion").isJsonNull()) {
                            emotion.set(message.get("emotion").getAsString());
                        }
                    }
                    case "error" -> {
                        error.set(message.has("message")
                                ? message.get("message").getAsString()
                                : message.toString());
                        utteranceDone.countDown();
                    }
                    default -> { }
                }
            }

            @Override
            public void onClose(int code, String reason) {
                utteranceDone.countDown();
            }
        });

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
    }

    private void closeConversation() {
        if (conversation != null) {
            try {
                conversation.close();
            } catch (Exception ignored) {
            }
            conversation = null;
        }
        utteranceOpen = false;
    }

    private void emitEvent(String type, Map<String, Object> fields) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", type);
        msg.putAll(fields);
        emit.accept(JsonUtil.dumps(msg));
    }

    private void emitError(String message) {
        log.warn("[speech] STT stream error: {}", message);
        emitEvent("error", Map.of("message", message));
    }
}
