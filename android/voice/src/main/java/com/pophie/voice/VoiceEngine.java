package com.pophie.voice;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.pophie.voice.dsp.Denoiser;
import com.pophie.voice.dsp.HighPassFilter;
import com.pophie.voice.dsp.NoOpDenoiser;
import com.pophie.voice.model.ModelManager;
import com.pophie.voice.source.AudioSource;
import com.pophie.voice.source.ExternalPcmSource;
import com.pophie.voice.source.MicSource;
import com.pophie.voice.speaker.SpeakerScorer;
import com.pophie.voice.vad.Endpointer;
import com.pophie.voice.vad.SileroVad;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 语音 SDK 门面：降噪 + VAD 断句 + 实时 PCM 输出 + 说话人可信度。
 * 线程模型：采集线程 → 帧队列 → 处理线程；回调切主线程。
 */
public final class VoiceEngine {

    private static final String TAG = "PophieVoiceEngine";
    public static final String OWNER_NAME = "__owner__";

    private final Context context;
    private final VoiceConfig config;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile VoiceListener listener;

    private volatile boolean running = false;
    private AudioSource source;
    private SileroVad vad;
    private SpeakerScorer scorer;
    private Endpointer endpointer;
    private Denoiser denoiser;
    private HighPassFilter highPass;

    private LinkedBlockingQueue<short[]> queue;
    private Thread procThread;

    private final int frameSamples;
    private final int frameMs;

    // 段状态
    private Segment seg;
    private boolean prevSpeaking = false;

    // preRoll 环形缓冲（输出帧 + 分析帧）
    private final Deque<short[]> preOut = new ArrayDeque<>();
    private final Deque<short[]> preAna = new ArrayDeque<>();
    private final int preRollFrames;

    public VoiceEngine(Context context, VoiceConfig config) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.frameSamples = config.frameSamples();
        this.frameMs = config.frameMs;
        this.preRollFrames = Math.max(0, config.preRollMs / Math.max(1, config.frameMs));
    }

    public void setListener(VoiceListener l) { this.listener = l; }

    // ---------------- 生命周期 ----------------

    public synchronized void start() {
        if (running) return;
        running = true;
        new Thread(this::init, "pophie-voice-init").start();
    }

    private void init() {
        try {
            if (config.source == AudioSourceType.MIC
                    && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                fail(new VoiceError(VoiceError.Code.PERMISSION_DENIED, "缺少 RECORD_AUDIO 权限"));
                running = false;
                return;
            }
            ensureEngineComponents();
            warmup();

            queue = new LinkedBlockingQueue<>(200);
            procThread = new Thread(this::processLoop, "pophie-voice-proc");
            procThread.start();

            if (config.source == AudioSourceType.MIC) {
                source = new MicSource(context, config.sampleRate, frameSamples,
                        config.enableSystemDenoise, config.enableAec, config.enableAgc);
            } else {
                source = new ExternalPcmSource(frameSamples);
            }
            boolean ok = source.start(this::onCaptureFrame);
            if (!ok) {
                fail(new VoiceError(VoiceError.Code.MIC_INIT_FAILED, "音频源启动失败"));
                running = false;
            }
        } catch (Throwable t) {
            Log.e(TAG, "init failed", t);
            fail(new VoiceError(VoiceError.Code.MODEL_LOAD_FAILED, "初始化失败: " + t.getMessage(), t));
            running = false;
        }
    }

    /** 构建 VAD/声纹/端点等（声纹可被 enroll 提前构建）。 */
    private synchronized void ensureEngineComponents() throws Exception {
        if (vad == null) {
            ModelManager mm = new ModelManager(context, config);
            String vadPath = mm.vadPath();
            AssetManager am = config.useAssets ? context.getAssets() : null;
            vad = new SileroVad(vadPath, am, config.vadThreshold, config.sampleRate,
                    config.vadWindowSize, config.numThreads);
        }
        ensureScorer();
        if (endpointer == null) {
            endpointer = new Endpointer(config.vadThreshold, config.minSpeechMs,
                    config.maxSilenceMs, config.maxSegmentMs, config.frameMs);
        }
        if (denoiser == null) denoiser = new NoOpDenoiser();
        if (highPass == null) highPass = new HighPassFilter();
    }

    /** 仅构建声纹（供 enroll 在未 start 时使用；会按需下载模型，请在后台线程调用）。 */
    public synchronized void ensureScorer() throws Exception {
        if (scorer != null) return;
        ModelManager mm = new ModelManager(context, config);
        String spkPath = mm.speakerPath();
        AssetManager am = config.useAssets ? context.getAssets() : null;
        File storeDir = config.modelDir != null ? new File(config.modelDir)
                : new File(context.getFilesDir(), "pophie-voice");
        scorer = new SpeakerScorer(spkPath, am, config.numThreads, config.sampleRate,
                config.ownerThreshold, config.emaAlpha, storeDir);
    }

    private void warmup() {
        try {
            float[] z = new float[config.vadWindowSize];
            vad.prob(z);
            scorer.embed(new short[config.sampleRate / 2]);
        } catch (Throwable t) {
            Log.w(TAG, "warmup skipped: " + t.getMessage());
        }
    }

    public synchronized void stop() {
        running = false;
        if (source != null) { try { source.stop(); } catch (Throwable ignored) {} source = null; }
        if (procThread != null) { procThread.interrupt(); procThread = null; }
        if (queue != null) queue.clear();
        if (vad != null) vad.reset();
        preOut.clear();
        preAna.clear();
        seg = null;
        prevSpeaking = false;
    }

    public boolean isRunning() { return running; }

    /** EXTERNAL_PCM 模式：上层喂入 PCM16。 */
    public void pushPcm(short[] pcm) {
        AudioSource s = source;
        if (s instanceof ExternalPcmSource) ((ExternalPcmSource) s).push(pcm);
    }

    // ---------------- 登记 ----------------

    /** 登记主人（多段）。需在后台线程调用（可能下载模型）。 */
    public synchronized void enrollOwner(List<short[]> samples) throws Exception {
        ensureScorer();
        scorer.enroll(OWNER_NAME, samples, true);
    }

    public synchronized void enroll(String name, List<short[]> samples) throws Exception {
        ensureScorer();
        scorer.enroll(name, samples, false);
    }

    /**
     * 用与运行时完全相同的采集路径(同 MicSource + 同 NS/AEC/AGC)登记主人，
     * 避免登记/识别前处理不一致导致的低匹配率。请在后台线程调用；调用前应先 stop()。
     * @return 实际采集的样本数
     */
    public synchronized int enrollOwnerFromMic(int ms) throws Exception {
        ensureScorer();
        short[] pcm = captureMs(ms);
        scorer.enroll(OWNER_NAME, java.util.Collections.singletonList(pcm), true);
        return pcm.length;
    }

    /** 自检：用相同采集路径录一段，返回与主人的原始 cosine（无主人或失败返回 -1）。后台线程调用。 */
    public synchronized float verifyOwnerFromMic(int ms) throws Exception {
        ensureScorer();
        short[] pcm = captureMs(ms);
        return scorer.ownerCosine(pcm);
    }

    /** 用 MicSource(与运行时一致)同步采集 ms 毫秒音频。 */
    private short[] captureMs(int ms) throws Exception {
        final int target = config.sampleRate * ms / 1000;
        final List<short[]> frames = java.util.Collections.synchronizedList(new ArrayList<>());
        final int[] count = {0};
        final Object lock = new Object();
        MicSource mic = new MicSource(context, config.sampleRate, frameSamples,
                config.enableSystemDenoise, config.enableAec, config.enableAgc);
        boolean ok = mic.start(frame -> {
            frames.add(frame);
            synchronized (lock) { count[0] += frame.length; lock.notifyAll(); }
        });
        if (!ok) throw new Exception("麦克风启动失败");
        long deadline = System.currentTimeMillis() + ms + 2000;
        try {
            synchronized (lock) {
                while (count[0] < target && System.currentTimeMillis() < deadline) {
                    lock.wait(200);
                }
            }
        } finally {
            mic.stop();
        }
        return WavUtil.concat(frames);
    }

    public synchronized boolean isOwnerEnrolled() {
        try { ensureScorer(); } catch (Exception e) { return false; }
        return scorer.isOwnerEnrolled();
    }

    public synchronized void clearSpeakers() {
        try { ensureScorer(); scorer.clear(); } catch (Exception ignored) {}
    }

    public synchronized String[] listSpeakers() {
        try { ensureScorer(); return scorer.speakerNames(); } catch (Exception e) { return new String[0]; }
    }

    // ---------------- 处理 ----------------

    private void onCaptureFrame(short[] frame) {
        if (queue == null) return;
        if (!queue.offer(frame)) {        // 队列满：丢最旧，保最新（实时优先）
            queue.poll();
            queue.offer(frame);
        }
    }

    private void processLoop() {
        while (running) {
            short[] frame;
            try {
                frame = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (frame == null) continue;
            try {
                process(frame);
            } catch (Throwable t) {
                Log.e(TAG, "process error", t);
            }
        }
    }

    private void process(short[] frame) {
        short[] outFrame = denoiser.process(frame);          // 输出路（系统已降噪）
        short[] anaFrame = highPass.process(frame);          // 分析路（轻处理）
        float prob = vad.prob(WavUtil.pcm16ToFloat(anaFrame));
        Endpointer.Event ev = endpointer.feed(prob);

        boolean speaking = endpointer.isSpeaking();
        if (speaking != prevSpeaking) {
            prevSpeaking = speaking;
            postSpeaking(speaking);
        }

        if (ev == Endpointer.Event.START) {
            startSegment();
        }

        if (seg != null) {
            // 累计
            seg.outFrames.add(outFrame);
            seg.analysisFrames.add(anaFrame);
            seg.analysisMs += frameMs;
            seg.msSinceScore += frameMs;

            maybeScore();
            routeOutput(outFrame);
        } else {
            // 非语音：维护 preRoll 环形缓冲
            pushPre(outFrame, anaFrame);
        }

        if (ev == Endpointer.Event.END) {
            endSegment();
        }
    }

    private void startSegment() {
        seg = new Segment();
        scorer.resetSmoothing();
        // 预卷：把静音前的 preRoll 帧并入段首
        for (short[] o : preOut) seg.outFrames.add(o);
        for (short[] a : preAna) { seg.analysisFrames.add(a); seg.analysisMs += frameMs; }
        // preRoll 输出帧也要实时吐出（带 PENDING）
        for (short[] o : preOut) routeOutput(o);
        preOut.clear();
        preAna.clear();
    }

    private void maybeScore() {
        boolean firstDue = !seg.scoredOnce && seg.analysisMs >= config.decisionWindowMs;
        boolean rescoreDue = seg.scoredOnce && seg.msSinceScore >= config.rescorePeriodMs;
        if (!firstDue && !rescoreDue) return;

        // 用输出路(与登记一致的原始/降噪信号)打分，避免高通分析路造成的域不匹配
        short[] window = WavUtil.concat(seg.outFrames);
        seg.msSinceScore = 0;
        if (WavUtil.rms(window) < config.minScoreRms) {
            // 能量过低：不打分，保持/置 UNKNOWN
            return;
        }
        SpeakerInfo info = scorer.score(window);
        seg.scoredOnce = true;
        SpeakerInfo prev = seg.current;
        seg.current = info;
        postSpeakerUpdated(info);

        // OWNER_ONLY：决策落定后处理挂起帧
        if (config.gateMode == GateMode.OWNER_ONLY) {
            boolean wasPending = prev == null || prev.state == SpeakerInfo.State.PENDING;
            if (info.isOwner) {
                if (!seg.pending.isEmpty()) {
                    for (short[] f : seg.pending) emitFrame(f, info);
                    seg.pending.clear();
                }
                seg.gateOpen = true;
            } else {
                seg.pending.clear();
                seg.gateOpen = false;
            }
        }
    }

    private void routeOutput(short[] outFrame) {
        SpeakerInfo cur = seg.current != null ? seg.current : SpeakerInfo.pending();
        if (config.gateMode == GateMode.REPORT) {
            emitFrame(outFrame, cur);
            return;
        }
        // OWNER_ONLY
        if (cur.state == SpeakerInfo.State.PENDING && !seg.scoredOnce) {
            seg.pending.add(outFrame);      // 决策前挂起
        } else if (seg.gateOpen) {
            emitFrame(outFrame, cur);
        } // 否则丢弃
    }

    private void endSegment() {
        if (seg == null) return;
        short[] pcm = WavUtil.concat(seg.outFrames);
        // 末次打分（整段）
        SpeakerInfo finalSpeaker = seg.current != null ? seg.current : SpeakerInfo.unknown(0f, 0f);
        float[] emb = null;
        try {
            short[] full = WavUtil.concat(seg.outFrames); // 与登记一致的信号
            if (WavUtil.rms(full) >= config.minScoreRms && !scorer.isEmpty()) {
                finalSpeaker = scorer.score(full);
                emb = scorer.embed(full);
            }
        } catch (Throwable ignored) {}
        VoiceSegment out = new VoiceSegment(pcm, config.sampleRate, finalSpeaker, emb);
        postSegment(out);
        seg = null;
    }

    private void pushPre(short[] out, short[] ana) {
        if (preRollFrames <= 0) return;
        preOut.addLast(out);
        preAna.addLast(ana);
        while (preOut.size() > preRollFrames) preOut.pollFirst();
        while (preAna.size() > preRollFrames) preAna.pollFirst();
    }

    // ---------------- 回调（主线程） ----------------

    private void emitFrame(short[] pcm, SpeakerInfo spk) {
        VoiceListener l = listener;
        if (l == null) return;
        float[] f = WavUtil.pcm16ToFloat(pcm);
        main.post(() -> l.onAudioFrame(pcm, f, config.sampleRate, spk));
    }

    private void postSpeaking(boolean speaking) {
        VoiceListener l = listener;
        if (l != null) main.post(() -> l.onSpeakingStateChanged(speaking));
    }

    private void postSpeakerUpdated(SpeakerInfo info) {
        VoiceListener l = listener;
        if (l != null) main.post(() -> l.onSpeakerUpdated(info));
    }

    private void postSegment(VoiceSegment s) {
        VoiceListener l = listener;
        if (l != null) main.post(() -> l.onSegmentEnd(s));
    }

    private void fail(VoiceError e) {
        VoiceListener l = listener;
        if (l != null) main.post(() -> l.onError(e));
    }

    private static final class Segment {
        final List<short[]> outFrames = new ArrayList<>();
        final List<short[]> analysisFrames = new ArrayList<>();
        final List<short[]> pending = new ArrayList<>(); // OWNER_ONLY 决策前挂起的输出帧
        int analysisMs = 0;
        int msSinceScore = 0;
        boolean scoredOnce = false;
        boolean gateOpen = false;
        SpeakerInfo current = SpeakerInfo.pending();
    }
}
