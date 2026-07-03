package com.pophie.service;

import com.pophie.schema.VoiceProsody;
import com.pophie.util.JsonUtil;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 统一回复通知 + 服务端 TTS 音频 NDJSON/WebSocket 事件发射器。
 * speak 文本立即推送；TTS 在后台合成后按 seq 顺序推送，不阻塞 LLM 生成。
 *
 * 并发保证：
 *   1. 所有 emit.accept() 调用都在 emitLock 保护下串行执行，杜绝 NDJSON 行交错
 *   2. 同一 emitter 内 TTS 合成最多 MAX_CONCURRENT_TTS 路并行
 *   3. TTS 输出严格按 seq 顺序发送：seq=N 的事件边到边发（无额外延迟），
 *      seq>N 的事件先缓冲，等 seq=N 的 speak_done 发出后才开始发送 seq=N+1
 */
public final class ReplyStreamEmitter {

    private static final int MAX_CONCURRENT_TTS = 2;

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

    private final Semaphore ttsConcurrency = new Semaphore(MAX_CONCURRENT_TTS, true);

    private final AtomicInteger nextSeqToSend = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, SeqBuffer> seqBuffers = new ConcurrentHashMap<>();

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
        emitLineSync(ev);
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
        emitLineSync(notify);

        Map<String, Object> speak = new LinkedHashMap<>();
        speak.put("type", "speak");
        speak.put("text", text);
        speak.put("seq", s);
        emitLineSync(speak);

        if (serverTts) {
            pendingTts.incrementAndGet();
            seqBuffers.put(s, new SeqBuffer());
            final int seqVal = s;
            final String textVal = text;
            bgExecutor.execute(() -> {
                try {
                    runTtsWithOrdering(textVal, seqVal);
                } finally {
                    pendingTts.decrementAndGet();
                    synchronized (pendingTts) {
                        pendingTts.notifyAll();
                    }
                }
            });
        }
    }

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
        emitLineSync(ev);
    }

    public void emitEvent(Map<String, Object> ev) {
        emitLineSync(ev);
    }

    private void runTtsWithOrdering(String text, int s) {
        boolean acquired = false;
        try {
            ttsConcurrency.acquire();
            acquired = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitTtsError(s, "TTS interrupted");
            return;
        }
        try {
            doTtsSynthesis(text, s);
        } finally {
            if (acquired) ttsConcurrency.release();
        }
    }

    private void doTtsSynthesis(String text, int s) {
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("type", "tts_meta");
            meta.put("seq", s);
            meta.put("format", speech.ttsStreamFormat());
            meta.put("sample_rate", speech.ttsSampleRate());
            meta.put("encoding", "base64");
            enqueueEvent(s, meta, false);

            speech.iterTtsChunks(text, defaultVoice, voiceId, null, chunk -> {
                byte[] copy = chunk.clone();
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("type", "tts_chunk");
                line.put("seq", s);
                line.put("data", Base64.getEncoder().encodeToString(copy));
                enqueueEvent(s, line, false);
            });

            Map<String, Object> end = new LinkedHashMap<>();
            end.put("type", "reply");
            end.put("phase", "speak_done");
            end.put("session_id", sessionId);
            end.put("seq", s);
            enqueueEvent(s, end, true);
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("type", "reply");
            err.put("phase", "tts_error");
            err.put("seq", s);
            err.put("message", e.getMessage());
            enqueueEvent(s, err, true);
        }
    }

    private void emitTtsError(int s, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("type", "reply");
        err.put("phase", "tts_error");
        err.put("seq", s);
        err.put("message", message);
        enqueueEvent(s, err, true);
    }

    /**
     * 将事件加入对应 seq 的缓冲队列，并在 emitLock 内尝试 drain。
     * @param isLast 该事件是否为该 seq 的最后一个（speak_done / tts_error）
     */
    private void enqueueEvent(int seqVal, Map<String, Object> event, boolean isLast) {
        synchronized (emitLock) {
            SeqBuffer buf = seqBuffers.get(seqVal);
            if (buf == null) {
                buf = new SeqBuffer();
                seqBuffers.put(seqVal, buf);
            }
            buf.events.add(event);
            if (isLast) {
                buf.done = true;
            }
            drainReadySeqs();
        }
    }

    /**
     * 在 emitLock 内调用，发送所有已就绪的事件：
     * - 对当前 nextSeqToSend 对应的 seq，立即发送所有已到达的事件（边到边发）
     * - 如果该 seq 已标记 done（最后事件已入队），则发送完后推进到下一个 seq，继续检查
     * - 如果该 seq 还有事件未到达，停止（等更多事件到来再 drain）
     */
    private void drainReadySeqs() {
        while (true) {
            int current = nextSeqToSend.get();
            SeqBuffer buf = seqBuffers.get(current);
            if (buf == null) break;

            for (Map<String, Object> ev : buf.events) {
                emitLine(ev);
            }
            buf.events.clear();

            if (!buf.done) break;

            seqBuffers.remove(current);
            nextSeqToSend.incrementAndGet();
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

    private static final class SeqBuffer {
        final List<Map<String, Object>> events = new ArrayList<>();
        volatile boolean done = false;
    }
}
