package com.pophie.voice.dsp;

/** 降噪接口（作用于输出路）。逐帧处理 PCM16，长度不变。 */
public interface Denoiser {
    short[] process(short[] frame);
    default void release() {}
}
