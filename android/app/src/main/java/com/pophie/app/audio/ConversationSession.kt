package com.pophie.app.audio

/**
 * 对话模式生命周期事件，供 XBot / MainActivity 等端侧模块订阅。
 *
 * - **插话（barge-in）**：停播 → 继续聆听，会话保持。
 * - **拒听（dismiss）**：停播 → 退出对话模式 → 待机，等下次唤醒。
 */
enum class ConversationInterruptKind {
    /** 用户开口打断，机器人停播后继续聆听。 */
    BARGE_IN,
    /** 用户明确拒听/结束对话，退出对话模式。 */
    DISMISS,
}

enum class ConversationEndReason {
    /** 用户说「别说了」「不想聊了」等，端侧拦截。 */
    USER_DISMISSED,
    /** 用户手动点按结束对话。 */
    USER_TOGGLE,
    /** 服务端 `session_end`（空闲超时等）。 */
    SESSION_IDLE,
    /** 其它错误导致结束。 */
    ERROR,
}

/** 对话会话相位（与 API 文档 §4.4 端侧本地 FSM 一致）。 */
object ConversationSessionFsm {
    const val IDLE = "idle"
    const val WAKING = "waking"
    const val LISTENING = "listening"
    const val THINKING = "thinking"
    const val SPEAKING = "speaking"

    fun fromPhase(phase: ConversationController.Phase): String = when (phase) {
        ConversationController.Phase.IDLE -> IDLE
        ConversationController.Phase.LISTENING -> LISTENING
        ConversationController.Phase.THINKING -> THINKING
        ConversationController.Phase.SPEAKING -> SPEAKING
    }
}

sealed class ConversationSessionEvent {
    /** 进入对话模式（唤醒后开麦）。 */
    data class Started(val fsmState: String = ConversationSessionFsm.WAKING) : ConversationSessionEvent()

    /** 会话相位变化。 */
    data class PhaseChanged(
        val phase: ConversationController.Phase,
        val fsmState: String,
    ) : ConversationSessionEvent()

    /** 用户插话：已停播，继续聆听。 */
    data object BargeIn : ConversationSessionEvent()

    /**
     * 用户拒听：已停播并退出对话。
     * @param text 触发拒听的 STT 文本（可为空）
     */
    data class Dismissed(val text: String) : ConversationSessionEvent()

    /** 对话结束（含原因）。 */
    data class Ended(
        val reason: ConversationEndReason,
        val message: String? = null,
    ) : ConversationSessionEvent()
}

/** 可选的回调式接入；与 [com.pophie.app.viewmodel.ChatViewModel.conversationEvents] 二选一或同时使用。 */
interface ConversationSessionListener {
    fun onConversationEvent(event: ConversationSessionEvent) {}
}
