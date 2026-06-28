package com.pophie.voice.vad;

/**
 * 纯逻辑端点检测状态机（可单测，不依赖 Android）。
 * 按帧喂入 VAD 语音概率,产出 START / END 事件,内置最短语音、尾点静音、最长段、hangover 防碎句。
 */
public final class Endpointer {

    public enum Event { NONE, START, END }

    private final float threshold;
    private final int minSpeechMs;
    private final int maxSilenceMs;
    private final int maxSegmentMs;
    private final int frameMs;

    private boolean speaking = false;
    private int speechMs = 0;     // 连续语音累计（未触发前）
    private int silenceMs = 0;    // 语音中累计静音
    private int segmentMs = 0;    // 当前段总时长

    public Endpointer(float threshold, int minSpeechMs, int maxSilenceMs, int maxSegmentMs, int frameMs) {
        this.threshold = threshold;
        this.minSpeechMs = minSpeechMs;
        this.maxSilenceMs = maxSilenceMs;
        this.maxSegmentMs = maxSegmentMs;
        this.frameMs = frameMs;
    }

    /** 喂入一帧语音概率,返回事件。 */
    public Event feed(float prob) {
        boolean voiced = prob >= threshold;
        if (!speaking) {
            if (voiced) {
                speechMs += frameMs;
                if (speechMs >= minSpeechMs) {
                    speaking = true;
                    silenceMs = 0;
                    segmentMs = speechMs;     // 计入起始累计
                    speechMs = 0;
                    return Event.START;
                }
            } else {
                speechMs = 0;
            }
            return Event.NONE;
        }

        // speaking == true
        segmentMs += frameMs;
        if (voiced) {
            silenceMs = 0;
        } else {
            silenceMs += frameMs;
        }
        if (silenceMs >= maxSilenceMs || segmentMs >= maxSegmentMs) {
            reset();
            return Event.END;
        }
        return Event.NONE;
    }

    public boolean isSpeaking() { return speaking; }

    public int currentSegmentMs() { return segmentMs; }

    public void reset() {
        speaking = false;
        speechMs = 0;
        silenceMs = 0;
        segmentMs = 0;
    }
}
