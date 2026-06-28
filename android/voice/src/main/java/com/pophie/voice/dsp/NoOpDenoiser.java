package com.pophie.voice.dsp;

/**
 * 直通降噪：默认实现。
 * 麦克风路径的实际降噪由系统 NoiseSuppressor 在采集会话上完成（见 AudioFxController），
 * 故此处直通即可；EXTERNAL_PCM 模式下若需强降噪，可替换为 RnnoiseDenoiser（预留）。
 */
public final class NoOpDenoiser implements Denoiser {
    @Override
    public short[] process(short[] frame) {
        return frame;
    }
}
