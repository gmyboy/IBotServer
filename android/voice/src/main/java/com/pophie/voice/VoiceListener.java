package com.pophie.voice;

/**
 * SDK 回调（全部在主线程触发）。默认空实现，按需重写。
 */
public interface VoiceListener {

    /** 是否检测到有人说话。 */
    default void onSpeakingStateChanged(boolean speaking) {}

    /**
     * 实时音频帧（放行的语音帧）+ 当前说话人可信度。
     * @param pcm16    PCM16 帧（输出路：已降噪）
     * @param pcmFloat 同一帧的 float [-1,1]（省一次转换，便于上层喂模型）
     * @param sampleRate 采样率
     * @param speaker  当前说话人判定（段首可能为 PENDING，随后 refine）
     */
    default void onAudioFrame(short[] pcm16, float[] pcmFloat, int sampleRate, SpeakerInfo speaker) {}

    /** 说话人判定更新（早出 → 多次 refine）。 */
    default void onSpeakerUpdated(SpeakerInfo speaker) {}

    /** 一段语音结束（含整段 WAV / embedding）。 */
    default void onSegmentEnd(VoiceSegment segment) {}

    /**
     * 采集线程同步回调，用于低延迟上行（如实时 STT）。
     * 与 {@link #onAudioFrame} 不同，不在主线程 post，且仅包含近场放行帧。
     */
    default void onAudioFrameSync(short[] pcm16, int sampleRate) {}

    /** 错误。 */
    default void onError(VoiceError error) {}
}
