package com.pophie.app.audio

import kotlin.math.sqrt

/** 轻量能量 VAD：对话模式内静音超时后触发 STT commit（云侧 turn_detection 的端侧兜底）。 */
object SpeechVad {
    const val SILENCE_MS = 600L
    /** TTS/思考期间：近场语音持续该时长即触发 barge-in（毫秒）。 */
    const val BARGE_IN_SPEECH_MS = 320L
    /** 近场说话 RMS 阈值；与 [ListeningAudioProcessor] 噪声门一致。 */
    const val MIN_SPEECH_RMS = 1400.0
    /** 连续超阈 chunk 数，达到后才认定进入说话段（约 200–400ms）。 */
    const val MIN_SPEECH_CHUNKS = 4
    /** 连续静音 chunk 数，达到后退出说话段。 */
    const val MIN_SILENCE_CHUNKS = 8

    fun isSpeech(pcm16: ByteArray): Boolean = rmsPcm16(pcm16) >= MIN_SPEECH_RMS

    fun rmsPcm16(pcm16: ByteArray): Double {
        if (pcm16.size < 2) return 0.0
        var sum = 0.0
        var i = 0
        while (i + 1 < pcm16.size) {
            val sample = (pcm16[i].toInt() and 0xFF) or (pcm16[i + 1].toInt() shl 8)
            val s = if (sample > 32767) sample - 65536 else sample
            sum += (s * s).toDouble()
            i += 2
        }
        val n = pcm16.size / 2
        return if (n == 0) 0.0 else sqrt(sum / n)
    }
}
