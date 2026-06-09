package com.pophie.app.audio

import com.pophie.app.data.model.FacialExpression
import com.pophie.app.data.model.VoiceProsody

/** 将表情 + 语音属性映射为 Android TTS 的语速/音调。 */
object VoiceProsodyMapper {

    data class SpeechParams(val rate: Float, val pitch: Float)

    fun map(expression: String?, voice: VoiceProsody?): SpeechParams {
        val expr = FacialExpression.fromKey(expression)
        var rate = 1.0f
        var pitch = 1.0f

        when (expr) {
            FacialExpression.HAPPY -> { pitch = 1.15f; rate = 1.05f }
            FacialExpression.SAD -> { pitch = 0.82f; rate = 0.88f }
            FacialExpression.ANGRY -> { pitch = 0.95f; rate = 1.18f }
            FacialExpression.FEAR -> { pitch = 1.12f; rate = 1.12f }
            FacialExpression.SURPRISE -> { pitch = 1.25f; rate = 1.15f }
            FacialExpression.DISGUST -> { pitch = 0.88f; rate = 0.95f }
            FacialExpression.NEUTRAL -> { pitch = 1.0f; rate = 1.0f }
        }

        when (voice?.speed) {
            "慢" -> rate *= 0.82f
            "快" -> rate *= 1.18f
            "极快" -> rate *= 1.35f
        }
        when (voice?.intonation) {
            "上扬" -> pitch *= 1.12f
            "下沉" -> pitch *= 0.88f
            "起伏大" -> pitch *= 1.08f
        }
        when (voice?.tone) {
            "温柔", "撒娇" -> {
                pitch *= 1.05f
                rate *= 0.95f
            }
            "低落" -> {
                pitch *= 0.9f
                rate *= 0.9f
            }
            "兴奋", "急躁" -> {
                rate *= 1.1f
            }
            "冷淡" -> {
                pitch *= 0.92f
                rate *= 0.95f
            }
        }

        return SpeechParams(
            rate = rate.coerceIn(0.5f, 2.0f),
            pitch = pitch.coerceIn(0.5f, 2.0f),
        )
    }
}
