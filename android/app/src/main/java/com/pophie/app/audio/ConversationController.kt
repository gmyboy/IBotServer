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
 * 对话模式编排：唤醒后建立 STT 流 → 聆听 → final →（由 ViewModel 触发 chat/TTS）→ 继续聆听。
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
    @Volatile private var paused = false
    @Volatile private var hasPartial = false
    @Volatile private var awaitingFinal = false
    @Volatile private var lastSpeechAt = 0L
    @Volatile private var silenceCommitMs = SpeechVad.SILENCE_MS

    fun isActive(): Boolean = active

    fun start() {
        if (active) return
        active = true
        paused = false
        hasPartial = false
        awaitingFinal = false
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
                    hasPartial = text.isNotBlank()
                    if (hasPartial) callbacks.onPartial(text)
                }

                override fun onFinal(text: String, voice: VoiceProsody?) {
                    hasPartial = false
                    awaitingFinal = false
                    if (!active || paused) return
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
        paused = false
        vadJob?.cancel()
        vadJob = null
        recorder.stopStreaming()
        sttClient?.close()
        sttClient = null
        callbacks.onPhase(Phase.IDLE)
    }

    fun setThinking() {
        paused = true
        callbacks.onPhase(Phase.THINKING)
    }

    fun setSpeaking() {
        paused = true
        callbacks.onPhase(Phase.SPEAKING)
    }

    /** LLM/TTS 结束后回到聆听（仍在对话模式内）。 */
    fun resumeListening() {
        if (!active) return
        paused = false
        hasPartial = false
        awaitingFinal = false
        lastSpeechAt = System.currentTimeMillis()
        ListeningAudioProcessor.reset()
        callbacks.onPhase(Phase.LISTENING)
        callbacks.onPartial("")
    }

    private fun startMic() {
        val ok = recorder.startStreaming { rawChunk ->
            if (!active || !ready || paused) return@startStreaming
            if (SpeechVad.isSpeech(rawChunk)) {
                lastSpeechAt = System.currentTimeMillis()
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
                if (!ready || paused || !hasPartial || awaitingFinal) continue
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
