package com.pophie.voice;

/** 一整段语音（VAD 断句结果），便利上层需要整段时使用。 */
public final class VoiceSegment {

    public final short[] pcm;
    public final int sampleRate;
    public final SpeakerInfo speaker;
    /** 该段最终声纹 embedding（可用于上层自定逻辑），可能为 null。 */
    public final float[] embedding;
    public final long durationMs;

    public VoiceSegment(short[] pcm, int sampleRate, SpeakerInfo speaker, float[] embedding) {
        this.pcm = pcm;
        this.sampleRate = sampleRate;
        this.speaker = speaker;
        this.embedding = embedding;
        this.durationMs = sampleRate > 0 ? (long) pcm.length * 1000L / sampleRate : 0L;
    }

    /** 转 WAV 字节（16-bit 单声道）。 */
    public byte[] toWav() {
        return WavUtil.shortToWav(pcm, sampleRate);
    }
}
