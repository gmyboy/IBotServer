package com.pophie.app.audio

/**
 * 过滤环境噪声触发的 ASR 幻觉：空文本、纯标点、无意义单字等。
 */
object SttNoiseFilter {

    private val SHORT_VALID = setOf(
        "好", "行", "嗯", "是", "对", "可", "不", "否", "停", "走", "开", "关",
        "谢", "拜", "嗨", "喂", "在", "有", "没", "要", "去", "来",
    )

    fun isLikelyNoise(text: String): Boolean {
        val t = text.trim()
        if (t.isBlank()) return true
        if (t.all { it.isWhitespace() || isPunctuation(it) }) return true
        if (t.length == 1 && t !in SHORT_VALID) return true
        return false
    }

    private fun isPunctuation(ch: Char): Boolean =
        ch in "，。！？、,.!?;:\"'()（）【】[]{}…—·"
}
