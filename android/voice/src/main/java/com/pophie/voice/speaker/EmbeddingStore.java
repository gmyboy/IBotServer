package com.pophie.voice.speaker;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登记说话人的 embedding 本地持久化（JSON，存模块私有目录，不出端）。
 * 结构：{ "owner": "<name>", "speakers": { name: [float,...] } }（已 L2 归一化）。
 */
public final class EmbeddingStore {

    private static final String TAG = "PophieVoiceStore";

    private final File file;
    private final Map<String, float[]> speakers = new LinkedHashMap<>();
    private String ownerName = null;
    private double ownerThreshold = 0; // 0 表示未自动标定，用默认阈值（裸 cosine 阈值，回退用）

    // AS-Norm 校准（cohort 可用时）
    private boolean ownerAsnorm = false;
    private double ownerCohortMean = 0;   // 主人质心相对 cohort 的 topK 均值
    private double ownerCohortStd = 1;    // 主人质心相对 cohort 的 topK 标准差
    private double ownerNormThreshold = 0;// 归一化分数阈值

    public EmbeddingStore(File dir) {
        if (!dir.exists()) dir.mkdirs();
        this.file = new File(dir, "speakers.json");
        load();
    }

    public synchronized Map<String, float[]> speakers() {
        return new LinkedHashMap<>(speakers);
    }

    public synchronized String ownerName() { return ownerName; }

    public synchronized double ownerThreshold() { return ownerThreshold; }

    public synchronized void setOwnerThreshold(double t) { ownerThreshold = t; save(); }

    public synchronized boolean ownerAsnorm() { return ownerAsnorm; }
    public synchronized double ownerCohortMean() { return ownerCohortMean; }
    public synchronized double ownerCohortStd() { return ownerCohortStd; }
    public synchronized double ownerNormThreshold() { return ownerNormThreshold; }

    public synchronized void setOwnerCalibration(double cohortMean, double cohortStd, double normThreshold) {
        this.ownerAsnorm = true;
        this.ownerCohortMean = cohortMean;
        this.ownerCohortStd = cohortStd <= 1e-6 ? 1 : cohortStd;
        this.ownerNormThreshold = normThreshold;
        save();
    }

    public synchronized void put(String name, float[] embedding, boolean isOwner) {
        speakers.put(name, embedding);
        if (isOwner) ownerName = name;
        save();
    }

    public synchronized void remove(String name) {
        speakers.remove(name);
        if (name.equals(ownerName)) ownerName = null;
        save();
    }

    public synchronized void clear() {
        speakers.clear();
        ownerName = null;
        ownerThreshold = 0;
        ownerAsnorm = false;
        ownerCohortMean = 0;
        ownerCohortStd = 1;
        ownerNormThreshold = 0;
        save();
    }

    public synchronized boolean isEmpty() { return speakers.isEmpty(); }

    private void load() {
        try {
            if (!file.exists()) return;
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(json);
            ownerName = root.optString("owner", null);
            ownerThreshold = root.optDouble("owner_threshold", 0);
            ownerAsnorm = root.optBoolean("owner_asnorm", false);
            ownerCohortMean = root.optDouble("owner_cohort_mean", 0);
            ownerCohortStd = root.optDouble("owner_cohort_std", 1);
            ownerNormThreshold = root.optDouble("owner_norm_threshold", 0);
            JSONObject sp = root.optJSONObject("speakers");
            if (sp != null) {
                for (java.util.Iterator<String> it = sp.keys(); it.hasNext(); ) {
                    String name = it.next();
                    JSONArray arr = sp.getJSONArray(name);
                    float[] emb = new float[arr.length()];
                    for (int i = 0; i < emb.length; i++) emb[i] = (float) arr.getDouble(i);
                    speakers.put(name, emb);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "load failed: " + t.getMessage());
        }
    }

    private void save() {
        try {
            JSONObject root = new JSONObject();
            if (ownerName != null) root.put("owner", ownerName);
            if (ownerThreshold > 0) root.put("owner_threshold", ownerThreshold);
            if (ownerAsnorm) {
                root.put("owner_asnorm", true);
                root.put("owner_cohort_mean", ownerCohortMean);
                root.put("owner_cohort_std", ownerCohortStd);
                root.put("owner_norm_threshold", ownerNormThreshold);
            }
            JSONObject sp = new JSONObject();
            for (Map.Entry<String, float[]> e : speakers.entrySet()) {
                JSONArray arr = new JSONArray();
                for (float v : e.getValue()) arr.put((double) v);
                sp.put(e.getKey(), arr);
            }
            root.put("speakers", sp);
            Files.write(file.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Log.w(TAG, "save failed: " + t.getMessage());
        }
    }
}
