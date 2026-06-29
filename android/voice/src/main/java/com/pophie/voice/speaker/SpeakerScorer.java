package com.pophie.voice.speaker;

import android.content.res.AssetManager;

import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;
import com.pophie.voice.SpeakerInfo;
import com.pophie.voice.WavUtil;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 声纹打分：sherpa-onnx 提 embedding，端侧与登记说话人做 cosine 比对，
 * 输出校准可信度 + margin + 次优 + 主人判定；段内 EMA 平滑。
 */
public final class SpeakerScorer {

    private final SpeakerEmbeddingExtractor extractor;
    private final EmbeddingStore store;
    private final int sampleRate;
    private final float threshold;
    private final float emaAlpha;

    private float smoothed = Float.NaN; // 段内 EMA 状态

    public SpeakerScorer(String modelPath, AssetManager assetManager, int numThreads,
                         int sampleRate, float threshold, float emaAlpha, File storeDir) {
        SpeakerEmbeddingExtractorConfig cfg =
                new SpeakerEmbeddingExtractorConfig(modelPath, numThreads, false, "cpu");
        this.extractor = new SpeakerEmbeddingExtractor(assetManager, cfg);
        this.store = new EmbeddingStore(storeDir);
        this.sampleRate = sampleRate;
        this.threshold = threshold;
        this.emaAlpha = emaAlpha;
    }

    /** 计算一段 PCM16 的归一化 embedding。 */
    public float[] embed(short[] pcm) {
        float[] f = WavUtil.pcm16ToFloat(pcm);
        OnlineStream stream = extractor.createStream();
        try {
            stream.acceptWaveform(f, sampleRate);
            stream.inputFinished();
            float[] emb = extractor.compute(stream);
            return l2norm(emb);
        } finally {
            try { stream.release(); } catch (Throwable ignored) {}
        }
    }

    /** 登记：多段取平均 embedding。isOwner=true 记为主人。登记前按能量裁掉首尾静音。 */
    public synchronized void enroll(String name, List<short[]> samples, boolean isOwner) {
        if (samples == null || samples.isEmpty()) return;
        float[] avg = null;
        int n = 0;
        for (short[] s : samples) {
            if (s == null || s.length == 0) continue;
            short[] trimmed = trimSilence(s);
            if (trimmed.length < sampleRate / 2) trimmed = s; // 太短则用原段
            float[] e = embed(trimmed);
            if (avg == null) avg = new float[e.length];
            for (int i = 0; i < e.length; i++) avg[i] += e[i];
            n++;
        }
        if (avg == null || n == 0) return;
        for (int i = 0; i < avg.length; i++) avg[i] /= n;
        store.put(name, l2norm(avg), isOwner);
    }

    /** 按 20ms 窗能量裁掉首尾静音，保留中间有声部分（提升登记质量）。 */
    private short[] trimSilence(short[] pcm) {
        int win = sampleRate / 50; // 20ms
        if (win <= 0 || pcm.length < win * 2) return pcm;
        int nWin = pcm.length / win;
        double[] rms = new double[nWin];
        double peak = 0;
        for (int w = 0; w < nWin; w++) {
            double sum = 0;
            int base = w * win;
            for (int i = 0; i < win; i++) { double v = pcm[base + i]; sum += v * v; }
            rms[w] = Math.sqrt(sum / win);
            if (rms[w] > peak) peak = rms[w];
        }
        double thr = Math.max(150.0, peak * 0.15);
        int first = -1, last = -1;
        for (int w = 0; w < nWin; w++) {
            if (rms[w] >= thr) { if (first < 0) first = w; last = w; }
        }
        if (first < 0 || last < first) return pcm;
        int start = first * win;
        int end = Math.min(pcm.length, (last + 1) * win);
        short[] out = new short[end - start];
        System.arraycopy(pcm, start, out, 0, out.length);
        return out;
    }

    public synchronized void remove(String name) { store.remove(name); }
    public synchronized void clear() { store.clear(); }
    public synchronized boolean isOwnerEnrolled() { return store.ownerName() != null; }
    public synchronized boolean isEmpty() { return store.isEmpty(); }
    public String[] speakerNames() { return store.speakers().keySet().toArray(new String[0]); }

    /** 每段开始时调用，清空 EMA。 */
    public synchronized void resetSmoothing() { smoothed = Float.NaN; }

    /**
     * 对一段 PCM 打分,返回当前说话人判定（已 EMA 平滑、校准 0~1）。
     * 无登记说话人时返回 UNKNOWN。
     */
    public synchronized SpeakerInfo score(short[] pcm) {
        Map<String, float[]> sp = store.speakers();
        if (sp.isEmpty()) return SpeakerInfo.unknown(0f);

        float[] emb = embed(pcm);
        String bestName = null, secondName = null;
        float bestCos = -2f, secondCos = -2f;
        for (Map.Entry<String, float[]> e : sp.entrySet()) {
            float c = cosine(emb, e.getValue());
            if (c > bestCos) {
                secondCos = bestCos; secondName = bestName;
                bestCos = c; bestName = e.getKey();
            } else if (c > secondCos) {
                secondCos = c; secondName = e.getKey();
            }
        }

        float conf = calibrate(bestCos);
        // 段内 EMA 平滑
        if (Float.isNaN(smoothed)) smoothed = conf;
        else smoothed = emaAlpha * conf + (1 - emaAlpha) * smoothed;

        float margin = (secondCos > -2f) ? (bestCos - secondCos) : bestCos;
        boolean decided = bestCos >= threshold;
        boolean isOwner = decided && bestName != null && bestName.equals(store.ownerName());
        SpeakerInfo.State state = decided ? SpeakerInfo.State.DECIDED : SpeakerInfo.State.UNKNOWN;
        String name = decided ? bestName : null;
        return new SpeakerInfo(name, smoothed, margin, secondName, isOwner, state);
    }

    public void release() {
        try { extractor.release(); } catch (Throwable ignored) {}
    }

    // cosine→0~1 校准（以 threshold 为中心的 logistic；阈值处=0.5）。需真机标定 k/threshold。
    private float calibrate(float cos) {
        double v = 1.0 / (1.0 + Math.exp(-12.0 * (cos - threshold)));
        return (float) Math.max(0.0, Math.min(1.0, v));
    }

    private static float cosine(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        double dot = 0;
        for (int i = 0; i < n; i++) dot += (double) a[i] * b[i];
        return (float) dot; // 已 L2 归一化,点积即 cosine
    }

    private static float[] l2norm(float[] v) {
        double s = 0;
        for (float x : v) s += (double) x * x;
        double norm = Math.sqrt(s);
        if (norm < 1e-9) return v;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / norm);
        return out;
    }
}
