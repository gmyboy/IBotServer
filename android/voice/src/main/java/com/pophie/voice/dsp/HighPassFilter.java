package com.pophie.voice.dsp;

/**
 * 分析路轻处理：一阶高通去直流/低频隆隆声（不做激进降噪，保音色，利于 VAD 与声纹）。
 * y[n] = a*(y[n-1] + x[n] - x[n-1])，a 接近 1（截止频率 ~80Hz @16k）。
 */
public final class HighPassFilter {

    private final float a;
    private float prevX = 0f;
    private float prevY = 0f;

    public HighPassFilter(float alpha) {
        this.a = alpha;
    }

    public HighPassFilter() {
        this(0.97f);
    }

    public synchronized short[] process(short[] frame) {
        short[] out = new short[frame.length];
        for (int i = 0; i < frame.length; i++) {
            float x = frame[i];
            float y = a * (prevY + x - prevX);
            prevX = x;
            prevY = y;
            if (y > 32767f) y = 32767f;
            if (y < -32768f) y = -32768f;
            out[i] = (short) y;
        }
        return out;
    }

    public synchronized void reset() {
        prevX = 0f;
        prevY = 0f;
    }
}
