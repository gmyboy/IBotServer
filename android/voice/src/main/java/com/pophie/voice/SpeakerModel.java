package com.pophie.voice;

/** 声纹模型档位。 */
public enum SpeakerModel {
    /** int8 量化小模型：更小更快，默认。 */
    SMALL_INT8,
    /** fp32 高精度模型：更准但更大更慢。 */
    ACCURATE,
}
