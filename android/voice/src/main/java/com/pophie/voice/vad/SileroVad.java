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

    /**
     * @param modelPath     silero_vad.onnx 路径（filesDir 绝对路径；assets 时为相对路径）
     * @param assetManager  useAssets 时传入，否则 null
     */
    public SileroVad(String modelPath, AssetManager assetManager,
                     float threshold, int sampleRate, int windowSize, int numThreads) {
        SileroVadModelConfig silero = new SileroVadModelConfig(
                modelPath,            // model
                threshold,            // threshold
                0.1f,                 // minSilenceDuration（我们自己做端点，这里给默认）
                0.1f,                 // minSpeechDuration
                windowSize,           // windowSize（Silero v5 = 512）
                20f                   // maxSpeechDuration
        );
        TenVadModelConfig ten = new TenVadModelConfig("", 0.5f, 0.1f, 0.1f, windowSize, 20f);
        VadModelConfig cfg = new VadModelConfig(silero, ten, sampleRate, numThreads, "cpu", false);
        this.vad = new Vad(assetManager, cfg);
    }

    /**
     * 喂入任意长度 float 帧（内部按 windowSize 缓冲，sherpa 标准用法）；
     * 返回当前窗口的语音概率（0.0~1.0 连续值），供 Endpointer 做阈值判断。
     */
    public synchronized float prob(float[] frame) {
        if (frame != null && frame.length > 0) {
            vad.acceptWaveform(frame);
        }
        // 我们用自己的 Endpointer 做端点，sherpa 自带的分段在此丢弃以释放内存
        while (!vad.empty()) vad.pop();
        return vad.isSpeechDetected() ? 1f : 0f;
    }

    public synchronized void reset() {
        try { vad.reset(); } catch (Throwable ignored) {}
    }

    public synchronized void release() {
        try { vad.release(); } catch (Throwable ignored) {}
    }
}
