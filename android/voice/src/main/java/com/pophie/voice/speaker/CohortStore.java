package com.pophie.voice.speaker;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 背景说话人 cohort（用于 AS-Norm 分数归一化与按冒充者定阈）。
 * 持久化到模块私有目录 cohort.json；embedding 均为 L2 归一化。
 */
public final class CohortStore {

    private static final String TAG = "PophieVoiceCohort";

    private final File file;
    private final List<float[]> embs = new ArrayList<>();

    public CohortStore(File dir) {
        if (!dir.exists()) dir.mkdirs();
        this.file = new File(dir, "cohort.json");
        load();
    }

    public synchronized void add(float[] emb) {
        embs.add(emb);
        save();
    }

    public synchronized List<float[]> list() {
        return new ArrayList<>(embs);
    }

    public synchronized int size() { return embs.size(); }

    public synchronized void clear() {
        embs.clear();
        save();
    }

    private void load() {
        try {
            if (!file.exists()) return;
            JSONObject root = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            JSONArray arr = root.optJSONArray("cohort");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONArray v = arr.getJSONArray(i);
                float[] e = new float[v.length()];
                for (int j = 0; j < e.length; j++) e[j] = (float) v.getDouble(j);
                embs.add(e);
            }
        } catch (Throwable t) {
            Log.w(TAG, "load failed: " + t.getMessage());
        }
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (float[] e : embs) {
                JSONArray v = new JSONArray();
                for (float x : e) v.put((double) x);
                arr.put(v);
            }
            JSONObject root = new JSONObject();
            root.put("cohort", arr);
            Files.write(file.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Log.w(TAG, "save failed: " + t.getMessage());
        }
    }
}
