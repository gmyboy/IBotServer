package com.pophie.app.audio

/**
 * 识别用户明确结束对话、要求机器人闭嘴的语句（端侧即时处理，不调 LLM）。
 */
object ConversationDismissDetector {

    private val PHRASES = listOf(
        "别说了",
        "别再说了",
        "不要说了",
        "不用说了",
        "闭嘴",
        "住口",
        "不想聊了",
        "不聊了",
        "不想说了",
        "不想听了",
        "不想继续聊",
        "不想继续对话",
        "不想继续",
        "安静点",
        "安静一下",
        "安静会儿",
        "烦死了",
        "别烦我",
        "结束对话",
        "退出对话",
        "停止对话",
        "不说了",
        "够了",
    )

    fun matches(text: String): Boolean {
        val t = normalize(text)
        if (t.length < 2) return false
        return PHRASES.any { phrase -> matchesPhrase(t, phrase) }
    }

    private fun normalize(text: String): String =
        text.trim()
            .replace(Regex("[，。！？、,.!?\\s]+"), "")

    /** 整句匹配，或短句以该短语开头（避免「别说了，我想说的是…」误判）。 */
    private fun matchesPhrase(text: String, phrase: String): Boolean {
        if (text == phrase) return true
        if (!text.startsWith(phrase)) return false
        val extra = text.length - phrase.length
        return extra <= 2
    }
}
