package com.pophie.voice.vad;

import android.content.res.AssetManager;

import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.TenVadModelConfig;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

/**
 * sherpa-onnx Silero VAD 薄封装：把任意长度 float 帧缓冲成 windowSize 窗口,
 * 逐窗口 compute() 取语音概率,返回最近一次概率。
 */
public final class SileroVad {

    private final Vad vad;
    private final int windowSize;
    private float[] buffer = new float[0];
    private float lastProb = 0f;

    /**
     * @param modelPath     silero_vad.onnx 路径（filesDir 绝对路径；assets 时为相对路径）
     * @param assetManager  useAssets 时传入，否则 null
     */
    public SileroVad(String modelPath, AssetManager assetManager,
                     float threshold, int sampleRate, int windowSize, int numThreads) {
        this.windowSize = windowSize;
        SileroVadModelConfig silero = new SileroVadModelConfig(
                modelPath,            // model
                threshold,            // threshold
                0.1f,                 // minSilenceDuration（我们自己做端点，这里给默认）
                0.1f,                 // minSpeechDuration
                windowSize,           // windowSize
                20f                   // maxSpeechDuration
        );
        TenVadModelConfig ten = new TenVadModelConfig("", 0.5f, 0.1f, 0.1f, windowSize, 20f);
        VadModelConfig cfg = new VadModelConfig(silero, ten, sampleRate, numThreads, "cpu", false);
        this.vad = new Vad(assetManager, cfg);
    }

    /** 喂入任意长度 float 帧；处理所有完整窗口后返回最近一次语音概率。 */
    public synchronized float prob(float[] frame) {
        if (frame != null && frame.length > 0) {
            float[] merged = new float[buffer.length + frame.length];
            System.arraycopy(buffer, 0, merged, 0, buffer.length);
            System.arraycopy(frame, 0, merged, buffer.length, frame.length);
            buffer = merged;
        }
        int off = 0;
        while (buffer.length - off >= windowSize) {
            float[] win = new float[windowSize];
            System.arraycopy(buffer, off, win, 0, windowSize);
            lastProb = vad.compute(win);
            off += windowSize;
        }
        if (off > 0) {
            int rem = buffer.length - off;
            float[] nb = new float[rem];
            System.arraycopy(buffer, off, nb, 0, rem);
            buffer = nb;
        }
        return lastProb;
    }

    public synchronized void reset() {
        buffer = new float[0];
        lastProb = 0f;
        try { vad.reset(); } catch (Throwable ignored) {}
    }

    public synchronized void release() {
        try { vad.release(); } catch (Throwable ignored) {}
    }
}
