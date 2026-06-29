package com.pophie.voice.speaker;

import android.content.res.AssetManager;

import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;
import com.pophie.voice.SpeakerInfo;
import com.pophie.voice.WavUtil;

import java.io.File;
import java.util.ArrayList;
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
    private final float defaultThreshold;  // 未自动标定时的回退阈值
    private final float emaAlpha;

    private float smoothed = Float.NaN; // 段内 EMA 状态

    public SpeakerScorer(String modelPath, AssetManager assetManager, int numThreads,
                         int sampleRate, float threshold, float emaAlpha, File storeDir) {
        SpeakerEmbeddingExtractorConfig cfg =
                new SpeakerEmbeddingExtractorConfig(modelPath, numThreads, false, "cpu");
        this.extractor = new SpeakerEmbeddingExtractor(assetManager, cfg);
        this.store = new EmbeddingStore(storeDir);
        this.sampleRate = sampleRate;
        this.defaultThreshold = threshold;
        this.emaAlpha = emaAlpha;
    }

    /** 当前生效阈值：优先用登记时自动标定的个性化阈值，否则用默认阈值。 */
    public synchronized float effectiveThreshold() {
        double t = store.ownerThreshold();
        return t > 0 ? (float) t : defaultThreshold;
    }

    public synchronized float currentOwnerThreshold() { return effectiveThreshold(); }

    /**
     * 自动标定登记：主人连续说一段(建议 ~8s 多句)，切成多窗算 embedding，
     * 质心作为主人声纹，并用"主人内部自相似分布"自动定阈值 = μ - 2σ（钳制到 [0.30,0.60]）。
     * @return 标定出的阈值
     */
    public synchronized double enrollOwnerAuto(String name, short[] longPcm) {
        short[] voiced = trimSilence(longPcm);
        if (voiced.length < sampleRate) voiced = longPcm; // < 1s 用原段
        int winLen = sampleRate * 2;            // 2s 窗
        int hop = (int) (sampleRate * 1.5);     // 1.5s 跳
        List<float[]> embs = new ArrayList<>();
        for (int start = 0; start + sampleRate <= voiced.length; start += hop) { // 至少 1s 才算一窗
            int end = Math.min(voiced.length, start + winLen);
            short[] w = new short[end - start];
            System.arraycopy(voiced, start, w, 0, w.length);
            embs.add(embed(w));
            if (end >= voiced.length) break;
        }
        if (embs.isEmpty()) embs.add(embed(voiced));

        int dim = embs.get(0).length;
        float[] centroid = new float[dim];
        for (float[] e : embs) for (int i = 0; i < dim; i++) centroid[i] += e[i];
        for (int i = 0; i < dim; i++) centroid[i] /= embs.size();
        centroid = l2norm(centroid);

        double thr;
        if (embs.size() >= 3) {
            double[] cos = new double[embs.size()];
            double sum = 0;
            for (int j = 0; j < embs.size(); j++) { cos[j] = cosine(embs.get(j), centroid); sum += cos[j]; }
            double mean = sum / embs.size();
            double var = 0;
            for (double c : cos) var += (c - mean) * (c - mean);
            var /= embs.size();
            double sd = Math.sqrt(var);
            thr = mean - 2.0 * sd;
        } else {
            // 样本太少：以质心自相似打个保守折扣
            thr = cosine(embs.get(0), centroid) - 0.12;
        }
        thr = Math.max(0.30, Math.min(0.60, thr));

        store.put(name, centroid, true);
        store.setOwnerThreshold(thr);
        return thr;
    }

    /** 计算一段 PCM16 的归一化 embedding（内部先去首尾静音，登记/打分一致）。 */
    public float[] embed(short[] pcm) {
        short[] voiced = trimSilence(pcm);
        if (voiced.length < sampleRate / 4) voiced = pcm; // < 0.25s 用原段，避免裁空
        float[] f = WavUtil.pcm16ToFloat(voiced);
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
            float[] e = embed(s); // embed 内部已去静音
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
        if (sp.isEmpty()) return SpeakerInfo.unknown(0f, 0f);

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

        float th = effectiveThreshold();
        float conf = calibrate(bestCos, th);
        // 段内 EMA 平滑
        if (Float.isNaN(smoothed)) smoothed = conf;
        else smoothed = emaAlpha * conf + (1 - emaAlpha) * smoothed;

        float margin = (secondCos > -2f) ? (bestCos - secondCos) : bestCos;
        boolean decided = bestCos >= th;
        boolean isOwner = decided && bestName != null && bestName.equals(store.ownerName());
        SpeakerInfo.State state = decided ? SpeakerInfo.State.DECIDED : SpeakerInfo.State.UNKNOWN;
        String name = decided ? bestName : null;
        return new SpeakerInfo(name, smoothed, margin, secondName, isOwner, state, bestCos);
    }

    /** 自检：返回一段音频与主人 embedding 的原始 cosine；无主人返回 -1。 */
    public synchronized float ownerCosine(short[] pcm) {
        String owner = store.ownerName();
        if (owner == null) return -1f;
        float[] o = store.speakers().get(owner);
        if (o == null) return -1f;
        return cosine(embed(pcm), o);
    }

    public void release() {
        try { extractor.release(); } catch (Throwable ignored) {}
    }

    // cosine→0~1 校准（以生效阈值为中心的 logistic；阈值处=0.5）。
    private float calibrate(float cos, float th) {
        double v = 1.0 / (1.0 + Math.exp(-12.0 * (cos - th)));
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
