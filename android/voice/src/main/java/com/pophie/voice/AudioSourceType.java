package com.pophie.voice;

/** 音频来源。 */
public enum AudioSourceType {
    /** SDK 内置麦克风采集（AudioRecord）。 */
    MIC,
    /** 由上层通过 VoiceEngine.pushPcm() 喂入 PCM（无麦克风也可用）。 */
    EXTERNAL_PCM,
}
