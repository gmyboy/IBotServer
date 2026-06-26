package com.pophie.app.audio

/**
 * 对话聆听增强：噪声门过滤环境/远场人声，AGC 提升近场说话音量，供 STT 使用。
 * VAD 仍应在原始 PCM 上判断（见 [ConversationController]）。
 * 低于门限时返回 null，调用方不应向云端发送该 chunk。
 */
object ListeningAudioProcessor {
    private const val TARGET_RMS = 4800.0
    private const val MIN_GAIN = 1.3
    private const val MAX_GAIN = 7.0
    private const val GAIN_ATTACK = 0.35
    private const val GAIN_RELEASE = 0.12
    /** 一阶高通，削弱低频环境嗡声（约 80Hz @ 16kHz）。 */
    private const val HP_ALPHA = 0.96

    private var smoothedGain = 1.0
    private var hpPrevIn = 0.0
    private var hpPrevOut = 0.0

    fun reset() {
        smoothedGain = 1.0
        hpPrevIn = 0.0
        hpPrevOut = 0.0
    }

    fun process(pcm16: ByteArray): ByteArray? {
        if (pcm16.size < 2) return null

        val rms = SpeechVad.rmsPcm16(pcm16)
        if (rms < SpeechVad.MIN_SPEECH_RMS) {
            smoothedGain += (1.0 - smoothedGain) * GAIN_RELEASE
            return null
        }

        val targetGain = (TARGET_RMS / rms).coerceIn(MIN_GAIN, MAX_GAIN)
        smoothedGain += (targetGain - smoothedGain) * GAIN_ATTACK

        val out = ByteArray(pcm16.size)
        var i = 0
        while (i + 1 < pcm16.size) {
            val sample = readSample(pcm16, i)
            val filtered = highPass(sample)
            val amplified = (filtered * smoothedGain).toInt().coerceIn(-32768, 32767)
            writeSample(out, i, amplified)
            i += 2
        }
        return out
    }

    private fun highPass(sample: Int): Int {
        val x = sample.toDouble()
        val y = HP_ALPHA * (hpPrevOut + x - hpPrevIn)
        hpPrevIn = x
        hpPrevOut = y
        return y.toInt().coerceIn(-32768, 32767)
    }

    private fun readSample(pcm16: ByteArray, i: Int): Int {
        val raw = (pcm16[i].toInt() and 0xFF) or (pcm16[i + 1].toInt() shl 8)
        return if (raw > 32767) raw - 65536 else raw
    }

    private fun writeSample(out: ByteArray, i: Int, sample: Int) {
        out[i] = (sample and 0xFF).toByte()
        out[i + 1] = ((sample shr 8) and 0xFF).toByte()
    }
}
