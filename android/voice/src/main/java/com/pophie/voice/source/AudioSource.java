package com.pophie.voice.source;

/** 音频源抽象：按固定帧长产出 PCM16 帧。 */
public interface AudioSource {

    interface FrameCallback {
        /** 一帧 PCM16（长度 = frameSamples）。 */
        void onFrame(short[] frame);
    }

    /** 开始；返回 false 表示初始化失败（如麦克风不可用）。 */
    boolean start(FrameCallback callback);

    void stop();

    boolean isRunning();

    /** 麦克风会话 id（用于挂系统音效）；外部源返回 0。 */
    int audioSessionId();
}
