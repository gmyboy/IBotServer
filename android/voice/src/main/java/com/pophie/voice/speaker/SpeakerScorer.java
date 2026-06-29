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

    private static final int MIN_COHORT_FOR_ASNORM = 8; // cohort 至少这么多才启用 AS-Norm
    private static final int ASNORM_TOPK = 20;          // AS-Norm 取 top-K 个 cohort 分数

    private final SpeakerEmbeddingExtractor extractor;
    private final EmbeddingStore store;
    private final CohortStore cohort;
    private final int sampleRate;
    private final float defaultThreshold;  // 未自动标定时的回退阈值（裸 cosine）
    private final float emaAlpha;

    private float smoothed = Float.NaN; // 段内 EMA 状态

    public SpeakerScorer(String modelPath, AssetManager assetManager, int numThreads,
                         int sampleRate, float threshold, float emaAlpha, File storeDir) {
        SpeakerEmbeddingExtractorConfig cfg =
                new SpeakerEmbeddingExtractorConfig(modelPath, numThreads, false, "cpu");
        this.extractor = new SpeakerEmbeddingExtractor(assetManager, cfg);
        this.store = new EmbeddingStore(storeDir);
        this.cohort = new CohortStore(storeDir);
        this.sampleRate = sampleRate;
        this.defaultThreshold = threshold;
        this.emaAlpha = emaAlpha;
    }

    // ---------------- cohort / AS-Norm ----------------

    /** 录入一条背景说话人 cohort 样本（其他人声）。cohort 越多越准。 */
    public synchronized void addCohort(short[] pcm) { cohort.add(embed(pcm)); }
    public synchronized int cohortSize() { return cohort.size(); }
    public synchronized void clearCohort() { cohort.clear(); }
    public synchronized boolean asnormActive() {
        return store.ownerAsnorm() && cohort.size() >= MIN_COHORT_FOR_ASNORM;
    }

    private double[] cohortStats(float[] emb) { return cohortStats(emb, false); }

    /** 计算 emb 相对 cohort 的 top-K 分数统计 {mean, std}；dropSelf 时剔除自匹配(≈1)。 */
    private double[] cohortStats(float[] emb, boolean dropSelf) {
        List<float[]> cs = cohort.list();
        List<Double> sl = new ArrayList<>();
        for (float[] g : cs) {
            double c = cosine(emb, g);
            if (dropSelf && c > 0.999) continue;
            sl.add(c);
        }
        if (sl.isEmpty()) return new double[]{0, 1};
        sl.sort(null);                                 // 升序
        int k = Math.min(ASNORM_TOPK, sl.size());
        double sum = 0;
        for (int i = sl.size() - k; i < sl.size(); i++) sum += sl.get(i);
        double mean = sum / k;
        double var = 0;
        for (int i = sl.size() - k; i < sl.size(); i++) var += (sl.get(i) - mean) * (sl.get(i) - mean);
        var /= k;
        double sd = Math.sqrt(var);
        return new double[]{mean, sd <= 1e-6 ? 1 : sd};
    }

    /** AS-Norm 归一化分数：测试 emb 与主人质心。 */
    private double asnormScore(float[] emb, float[] ownerCentroid) {
        double cos = cosine(emb, ownerCentroid);
        double[] st = cohortStats(emb);
        double zTest = (cos - st[0]) / st[1];
        double zEnroll = (cos - store.ownerCohortMean()) / store.ownerCohortStd();
        return 0.5 * (zTest + zEnroll);
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

        store.put(name, centroid, true);
        store.setOwnerThreshold(0); // 清掉旧版可能残留的高估阈值，无 cohort 时回退默认

        // 注意：不用"单段 μ-2σ"自估阈值——同一段登记音频各窗高度相似，σ 极小会把阈值高估到 ~0.6，
        // 导致跨句/隔时说话时同一个人也够不到。正确做法是 AS-Norm（需 cohort）；无 cohort 时用固定默认阈值。
        if (cohort.size() >= MIN_COHORT_FOR_ASNORM) {
            double[] oc = cohortStats(centroid);            // 主人质心相对 cohort 的 μc/σc
            List<Double> gen = new ArrayList<>();            // 真人(各登记窗)归一化分
            for (float[] w : embs) {
                double cos = cosine(w, centroid);
                double[] sw = cohortStats(w);
                gen.add(0.5 * ((cos - sw[0]) / sw[1] + (cos - oc[0]) / oc[1]));
            }
            List<Double> imp = new ArrayList<>();            // 冒充者(各 cohort)归一化分
            for (float[] g : cohort.list()) {
                double cos = cosine(g, centroid);
                double[] sg = cohortStats(g, true);          // 剔除自匹配
                imp.add(0.5 * ((cos - sg[0]) / sg[1] + (cos - oc[0]) / oc[1]));
            }
            double nth = chooseThreshold(gen, imp);
            store.setOwnerCalibration(oc[0], oc[1], nth);
            return nth;
        }
        // 无 cohort：用默认阈值（可由 setOwnerThreshold 手动微调）
        return defaultThreshold;
    }

    /** 手动设置裸 cosine 阈值（无 cohort 路径用；AS-Norm 生效时以归一化阈值为准）。 */
    public synchronized void setOwnerThreshold(float t) { store.setOwnerThreshold(t); }

    /** 两高斯交叉点近似(EER)：阈值卡在主人分布与冒充分布之间。 */
    private static double chooseThreshold(List<Double> genuine, List<Double> impostor) {
        if (genuine.isEmpty()) return 1.5;
        double mg = mean(genuine), sg = std(genuine, mg);
        if (impostor.isEmpty()) return mg - 2 * sg;
        double mi = mean(impostor), si = std(impostor, mi);
        if (sg <= 1e-6) sg = 1e-6;
        if (si <= 1e-6) si = 1e-6;
        double thr = (mg * si + mi * sg) / (sg + si); // 按标准差加权的中点
        double lo = mi + 0.2, hi = mg - 0.2;
        if (lo < hi) thr = Math.max(lo, Math.min(hi, thr));
        return thr;
    }

    private static double mean(List<Double> xs) {
        double s = 0; for (double x : xs) s += x; return s / xs.size();
    }

    private static double std(List<Double> xs, double m) {
        double v = 0; for (double x : xs) v += (x - m) * (x - m); return Math.sqrt(v / xs.size());
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

        // AS-Norm 主人门控（cohort 足够时优先）：归一化分数更稳、阈值跨条件一致
        String owner = store.ownerName();
        if (asnormActive() && owner != null && sp.get(owner) != null) {
            float[] oc = sp.get(owner);
            float cosO = cosine(emb, oc);
            double ns = asnormScore(emb, oc);
            double nth = store.ownerNormThreshold();
            boolean decided = ns >= nth;
            float conf = (float) clamp01(1.0 / (1.0 + Math.exp(-(ns - nth))));
            if (Float.isNaN(smoothed)) smoothed = conf; else smoothed = emaAlpha * conf + (1 - emaAlpha) * smoothed;
            SpeakerInfo.State state = decided ? SpeakerInfo.State.DECIDED : SpeakerInfo.State.UNKNOWN;
            return new SpeakerInfo(decided ? owner : null, smoothed, (float) (ns - nth), null,
                    decided, state, cosO);
        }

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

    /** 自检分数：AS-Norm 启用时返回归一化分，否则裸 cosine；无主人返回 NaN。 */
    public synchronized float ownerSelfScore(short[] pcm) {
        String owner = store.ownerName();
        if (owner == null) return Float.NaN;
        float[] o = store.speakers().get(owner);
        if (o == null) return Float.NaN;
        float[] e = embed(pcm);
        return asnormActive() ? (float) asnormScore(e, o) : cosine(e, o);
    }

    /** 自检对应的判定阈值（与 ownerSelfScore 同一量纲）。 */
    public synchronized float ownerSelfThreshold() {
        return asnormActive() ? (float) store.ownerNormThreshold() : effectiveThreshold();
    }

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }

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
