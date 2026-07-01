package com.pophie.service;

import com.pophie.schema.VoiceProsody;
import com.pophie.util.JsonUtil;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 统一「回复通知 + 服务端 TTS 音频」NDJSON/WebSocket 事件发射器。
 * speak 文本立即推送；TTS 在后台合成后按 seq 顺序推送，不阻塞 LLM 生成。
 */
public final class ReplyStreamEmitter {

    private final Consumer<String> emit;
    private final Object emitLock;
    private final SpeechService speech;
    private final Executor bgExecutor;
    private final AtomicInteger seq = new AtomicInteger(0);
    private final AtomicInteger pendingTts = new AtomicInteger(0);
    private final String sessionId;
    private final boolean serverTts;
    private final VoiceProsody defaultVoice;
    private final String voiceId;

    public ReplyStreamEmitter(String sessionId, boolean serverTts, VoiceProsody voice, String voiceId,
                              SpeechService speech, Executor bgExecutor, Object emitLock,
                              Consumer<String> emit) {
        this.sessionId = sessionId;
        this.serverTts = serverTts && speech != null && speech.isEnabled();
        this.defaultVoice = voice;
        this.voiceId = voiceId;
        this.speech = speech;
        this.bgExecutor = bgExecutor;
        this.emitLock = emitLock;
        this.emit = emit;
    }

    public void replyStart(String source) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", "reply");
        ev.put("phase", "start");
        ev.put("session_id", sessionId);
        ev.put("source", source == null ? "chat" : source);
        emitLine(ev);
    }

    public void emitSpeak(String text) {
        if (text == null || text.isBlank()) return;
        int s = seq.incrementAndGet();
        Map<String, Object> notify = new LinkedHashMap<>();
        notify.put("type", "reply");
        notify.put("phase", "speak");
        notify.put("session_id", sessionId);
        notify.put("seq", s);
        notify.put("text", text);
        emitLine(notify);

        Map<String, Object> speak = new LinkedHashMap<>();
        speak.put("type", "speak");
        speak.put("text", text);
        speak.put("seq", s);
        emitLine(speak);

        if (serverTts) {
            pendingTts.incrementAndGet();
            bgExecutor.execute(() -> {
                try {
                    emitTtsForSeq(text, s);
                } finally {
                    pendingTts.decrementAndGet();
                    synchronized (pendingTts) {
                        pendingTts.notifyAll();
                    }
                }
            });
        }
    }

    /** 等待后台 TTS 推送完成（chat/stream 收尾前调用）。 */
    public void awaitPendingTts(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (pendingTts) {
            while (pendingTts.get() > 0) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) break;
                try {
                    pendingTts.wait(remain);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    public void replyDone() {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", "reply");
        ev.put("phase", "done");
        ev.put("session_id", sessionId);
        emitLine(ev);
    }

    private void emitTtsForSeq(String text, int s) {
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("type", "tts_meta");
            meta.put("seq", s);
            meta.put("format", speech.ttsStreamFormat());
            meta.put("sample_rate", speech.ttsSampleRate());
            meta.put("encoding", "base64");
            emitLineSync(meta);

            speech.iterTtsChunks(text, defaultVoice, voiceId, null, chunk -> {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("type", "tts_chunk");
                line.put("seq", s);
                line.put("data", Base64.getEncoder().encodeToString(chunk));
                emitLineSync(line);
            });

            Map<String, Object> end = new LinkedHashMap<>();
            end.put("type", "reply");
            end.put("phase", "speak_done");
            end.put("session_id", sessionId);
            end.put("seq", s);
            emitLineSync(end);
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("type", "reply");
            err.put("phase", "tts_error");
            err.put("seq", s);
            err.put("message", e.getMessage());
            emitLineSync(err);
        }
    }

    private void emitLineSync(Map<String, Object> ev) {
        if (emitLock != null) {
            synchronized (emitLock) {
                emitLine(ev);
            }
        } else {
            emitLine(ev);
        }
    }

    private void emitLine(Map<String, Object> ev) {
        emit.accept(JsonUtil.dumps(ev) + "\n");
    }
}
