package com.pophie.app.audio

/**
 * 单轮 utterance 附带的端侧感知快照（XBot 在 final 前持续更新）。
 */
data class UtterancePerception(
    val facialExpression: String? = null,
    val identity: String? = null,
)
