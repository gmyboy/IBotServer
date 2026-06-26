package com.pophie.app.audio

import android.content.Context
import android.util.Log
import com.pophie.app.data.model.VoiceProsody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 对话模式编排：流式 STT、barge-in（插话→聆听）、dismiss（厌烦拒听→待机）。
 */
class ConversationController(
    private val context: Context,
    private val baseUrl: String,
    private val scope: CoroutineScope,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onPhase(phase: Phase)
        fun onPartial(text: String)
        fun onFinal(text: String, voice: VoiceProsody?)
        /** 用户插话：停播并继续聆听。 */
        fun onBargeIn()
        /** 用户明确拒听/结束对话：停播并退出对话模式（待机，等下次唤醒）。 */
        fun onConversationDismissed(text: String)
        fun onError(message: String)
        fun onSessionEnded(reason: String, message: String) {}
    }

    enum class Phase {
        IDLE,
        LISTENING,
        THINKING,
        SPEAKING,
    }

    private val recorder = AudioRecorder(context)
    private var sttClient: SttStreamClient? = null
    private var vadJob: Job? = null
    @Volatile private var active = false
    @Volatile private var ready = false
    @Volatile private var phase = Phase.IDLE
    @Volatile private var hasPartial = false
    @Volatile private var awaitingFinal = false
    @Volatile private var lastSpeechAt = 0L
    @Volatile private var speechBurstStart = 0L
    @Volatile private var bargeInDone = false
    @Volatile private var silenceCommitMs = SpeechVad.SILENCE_MS

    fun isActive(): Boolean = active

    fun start() {
        if (active) return
        active = true
        phase = Phase.LISTENING
        hasPartial = false
        awaitingFinal = false
        bargeInDone = false
        speechBurstStart = 0L
        ListeningAudioProcessor.reset()
        callbacks.onPhase(Phase.LISTENING)

        val client = SttStreamClient(SttStreamClient.wsUrlFromHttp(baseUrl))
        sttClient = client
        client.connect(
            listener = object : SttStreamClient.Listener {
                override fun onMeta(sampleRate: Int, turnDetection: Boolean, silenceCommitMs: Int, conversationIdleSec: Int) {
                    this@ConversationController.silenceCommitMs = silenceCommitMs.toLong()
                }

                override fun onReady() {
                    ready = true
                    startMic()
                    startVadLoop()
                }

                override fun onPartial(text: String) {
                    val trimmed = text.trim()
                    if (trimmed.isBlank()) return
                    if (ConversationDismissDetector.matches(trimmed)) {
                        triggerDismissConversation(trimmed, "partial")
                        return
                    }
                    hasPartial = true
                    if (phase == Phase.SPEAKING || phase == Phase.THINKING) {
                        triggerBargeIn("partial")
                    }
                    callbacks.onPartial(trimmed)
                }

                override fun onFinal(text: String, voice: VoiceProsody?) {
                    hasPartial = false
                    awaitingFinal = false
                    bargeInDone = false
                    speechBurstStart = 0L
                    if (!active) return
                    val trimmed = text.trim()
                    if (ConversationDismissDetector.matches(trimmed)) {
                        triggerDismissConversation(trimmed, "final")
                        return
                    }
                    callbacks.onFinal(text, voice)
                }

                override fun onError(message: String) {
                    callbacks.onError(message)
                }

                override fun onSessionEnd(reason: String, message: String) {
                    if (!active) return
                    callbacks.onSessionEnded(reason, message)
                    stop()
                }

                override fun onClosed() {
                    ready = false
                }
            },
        )
    }

    fun stop() {
        if (!active) return
        active = false
        ready = false
        phase = Phase.IDLE
        vadJob?.cancel()
        vadJob = null
        recorder.stopStreaming()
        sttClient?.close()
        sttClient = null
        callbacks.onPhase(Phase.IDLE)
    }

    fun setThinking() {
        phase = Phase.THINKING
        callbacks.onPhase(Phase.THINKING)
    }

    fun setSpeaking() {
        phase = Phase.SPEAKING
        bargeInDone = false
        speechBurstStart = 0L
        callbacks.onPhase(Phase.SPEAKING)
    }

    fun resumeListening() {
        if (!active) return
        phase = Phase.LISTENING
        hasPartial = false
        awaitingFinal = false
        bargeInDone = false
        speechBurstStart = 0L
        lastSpeechAt = System.currentTimeMillis()
        ListeningAudioProcessor.reset()
        callbacks.onPhase(Phase.LISTENING)
        callbacks.onPartial("")
    }

    private fun triggerBargeIn(source: String) {
        if (bargeInDone || phase == Phase.LISTENING || phase == Phase.IDLE) return
        bargeInDone = true
        phase = Phase.LISTENING
        Log.d(TAG, "barge-in via $source")
        callbacks.onBargeIn()
        callbacks.onPhase(Phase.LISTENING)
    }

    private fun triggerDismissConversation(text: String, source: String) {
        if (!active) return
        Log.d(TAG, "dismiss conversation via $source text=$text")
        callbacks.onConversationDismissed(text)
        stop()
    }

    private fun startMic() {
        val ok = recorder.startStreaming { rawChunk ->
            if (!active || !ready) return@startStreaming
            val now = System.currentTimeMillis()
            val isSpeech = SpeechVad.isSpeech(rawChunk)
            if (isSpeech) {
                lastSpeechAt = now
                if (phase == Phase.SPEAKING || phase == Phase.THINKING) {
                    if (speechBurstStart == 0L) {
                        speechBurstStart = now
                    } else if (now - speechBurstStart >= SpeechVad.BARGE_IN_SPEECH_MS) {
                        triggerBargeIn("vad")
                    }
                } else {
                    speechBurstStart = 0L
                }
            } else if (phase == Phase.LISTENING) {
                speechBurstStart = 0L
            }

            sttClient?.sendChunk(ListeningAudioProcessor.process(rawChunk))
        }
        if (!ok) {
            callbacks.onError("无法启动麦克风")
            stop()
        }
    }

    private fun startVadLoop() {
        vadJob?.cancel()
        vadJob = scope.launch {
            while (isActive && active) {
                delay(100)
                if (!ready || phase != Phase.LISTENING || !hasPartial || awaitingFinal) continue
                val silentMs = System.currentTimeMillis() - lastSpeechAt
                if (silentMs >= silenceCommitMs) {
                    awaitingFinal = true
                    sttClient?.commit()
                    Log.d(TAG, "VAD commit after ${silentMs}ms silence")
                }
            }
        }
    }

    companion object {
        private const val TAG = "PophieConv"
    }
}
