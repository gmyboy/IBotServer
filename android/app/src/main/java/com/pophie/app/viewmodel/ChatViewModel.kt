package com.pophie.app.viewmodel

import android.app.Application
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pophie.app.audio.RobotSpeaker
import com.pophie.app.audio.AudioRecorder
import com.pophie.app.audio.AudioRouteHelper
import com.pophie.app.audio.ChatStreamClient
import com.pophie.app.audio.ConversationController
import com.pophie.app.audio.ConversationDismissDetector
import com.pophie.app.audio.ConversationEndReason
import com.pophie.app.audio.ConversationInterruptKind
import com.pophie.app.audio.ConversationSessionEvent
import com.pophie.app.audio.ConversationSessionFsm
import com.pophie.app.audio.ConversationSessionListener
import com.pophie.app.audio.UtterancePerception
import com.pophie.app.data.ApiClient
import com.pophie.app.data.PophieApi
import com.pophie.app.data.model.AudioPayload
import com.pophie.app.data.model.ChatInput
import com.pophie.app.data.model.ChatRequest
import com.pophie.app.data.model.BodyAction
import com.pophie.app.data.model.FacialExpression
import com.pophie.app.data.model.GestureAction
import com.pophie.app.data.model.PerceptionInput
import com.pophie.app.data.model.PostureAction
import com.pophie.app.data.model.RobotAction
import com.pophie.app.data.model.RobotGesture
import com.pophie.app.data.model.RobotOutput
import com.pophie.app.data.model.RobotPosture
import com.pophie.app.data.model.RobotState
import com.pophie.app.data.model.TtsVoices
import com.pophie.app.data.model.VoiceProsody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatMessage(
    val id: Long,
    val role: String,
    val text: String,
    val expression: String? = null,
    val stateLabel: String? = null,
    val voiceLabel: String? = null,
    val timbreLabel: String? = null,
)

data class ChatUiState(
    val robotId: String = "",
    val sessionId: String = "",
    val sessionLabel: String = "连接中…",
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val selectedExpression: FacialExpression? = null,
    val selectedAction: BodyAction? = null,
    val selectedRobotAction: RobotAction? = null,
    val selectedGesture: RobotGesture? = null,
    val selectedPosture: RobotPosture? = null,
    val pendingMessageId: Long? = null,
    val isRecording: Boolean = false,
    val isSpeaking: Boolean = false,
    val speechEnabled: Boolean = false,
    val voiceId: String = ApiClient.DEFAULT_VOICE_ID,
    val voiceLabel: String = "",
    val error: String? = null,
    val inputMode: InputMode = InputMode.TEXT,
    val conversationActive: Boolean = false,
    val conversationPhase: ConversationController.Phase = ConversationController.Phase.IDLE,
    /** 对话会话相位 FSM（idle/waking/listening/thinking/speaking），供虚拟宠物/状态栏展示。 */
    val sessionFsmState: String = ConversationSessionFsm.IDLE,
    /** 最近一次打断类型（插话 / 拒听），对话结束后保留直至下次进入。 */
    val lastInterruptKind: ConversationInterruptKind? = null,
    /** 短提示文案（插话、拒听等），UI 可定时清除。 */
    val conversationStatusHint: String? = null,
    val partialTranscript: String = "",
)

enum class InputMode {
    TEXT,
    VOICE,
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _conversationEvents = MutableSharedFlow<ConversationSessionEvent>(extraBufferCapacity = 16)
    val conversationEvents: SharedFlow<ConversationSessionEvent> = _conversationEvents.asSharedFlow()

    private var conversationSessionListener: ConversationSessionListener? = null

    private val recorder = AudioRecorder(app)
    private val speaker = RobotSpeaker(app)
    private var conversationController: ConversationController? = null
    private var conversationSendJob: Job? = null
    private var activeSpeakJob: Job? = null
    @Volatile private var speakGeneration = 0
    private var livePerception = UtterancePerception()
    private var msgId = 0L
    private var proactiveSinceId = 0L
    private var pollJob: Job? = null
    private var initJob: Job? = null
    private val speakMutex = Mutex()

    init {
        if (ApiClient.isActivated(getApplication())) {
            initSession()
        }
    }

    /** 首次激活向导完成后调用：注册主人档案并展示欢迎语。 */
    fun onActivationComplete() {
        pollJob?.cancel()
        initJob?.cancel()
        proactiveSinceId = 0L
        _uiState.update { it.copy(messages = emptyList()) }
        initSession(isActivation = true)
    }

    private fun initSession(forceNewSession: Boolean = false, isActivation: Boolean = false) {
        initJob?.cancel()
        initJob = viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val api = ApiClient.api(app)
                val health = api.health()
                val robotId = ApiClient.getOrCreateRobotId(app)
                var sessionId = if (forceNewSession) null else ApiClient.getSessionId(app)
                if (sessionId.isNullOrBlank()) {
                    val session = api.newSession(robotId = robotId)
                    sessionId = session.sessionId
                    ApiClient.saveSessionId(app, sessionId)
                }
                val ownerResp = ApiClient.syncOwnerProfile(app, sessionId)
                if (!ownerResp?.sessionId.isNullOrBlank()) {
                    sessionId = ownerResp!!.sessionId!!
                    ApiClient.saveSessionId(app, sessionId)
                }
                var welcomed = false
                if (!ownerResp?.welcomeMessage.isNullOrBlank()) {
                    showWelcomeMessage(ownerResp!!.welcomeMessage!!, health.speechEnabled)
                    welcomed = true
                }
                if (!welcomed && isActivation) {
                    val pm = api.proactiveMessages(
                        robotId = robotId,
                        sessionId = sessionId,
                        sinceId = 0,
                    )
                    for (item in pm.items) {
                        if (item.content.isBlank()) continue
                        showWelcomeMessage(item.content, health.speechEnabled)
                        welcomed = true
                    }
                }
                proactiveSinceId = syncProactiveCursor(api, robotId, sessionId!!)
                val owner = ApiClient.getOwnerProfile(app)
                val robotLabel = owner?.robotName?.let { " · $it" } ?: ""
                _uiState.update {
                    it.copy(
                        robotId = robotId,
                        sessionId = sessionId,
                        speechEnabled = health.speechEnabled,
                        voiceId = ApiClient.getVoiceId(app),
                        voiceLabel = TtsVoices.labelFor(ApiClient.getVoiceId(app)),
                        sessionLabel = "${ApiClient.robotIdShort(robotId)} / $sessionId$robotLabel" +
                            if (health.speechEnabled) " · 语音已启用" else " · 仅文本",
                        error = null,
                    )
                }
                startProactivePolling()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        sessionLabel = "连接失败",
                        error = "无法连接服务器：${e.message}。请在设置中配置 Base URL。",
                    )
                }
            }
        }
    }

    private suspend fun syncProactiveCursor(
        api: PophieApi,
        robotId: String,
        sessionId: String,
    ): Long = try {
        api.proactiveMessages(
            robotId = robotId,
            sessionId = sessionId,
            sinceId = proactiveSinceId,
        ).lastId
    } catch (_: Exception) {
        proactiveSinceId
    }

    private suspend fun showWelcomeMessage(text: String, speechEnabled: Boolean) {
        val msg = ChatMessage(
            id = ++msgId,
            role = "proactive",
            text = text,
            expression = "neutral",
        )
        _uiState.update { it.copy(messages = it.messages + msg) }
        if (speechEnabled) {
            withContext(Dispatchers.Main) {
                AudioRouteHelper.prepareForPlayback(getApplication())
            }
            val voiceId = ApiClient.getVoiceId(getApplication())
            speakRobot(text = text, serverAudio = null, voiceId = voiceId)
        }
    }

    fun startNewConversation() {
        pollJob?.cancel()
        initJob?.cancel()
        ApiClient.clearSessionId(getApplication())
        _uiState.update { it.copy(messages = emptyList()) }
        proactiveSinceId = 0L
        initSession(forceNewSession = true)
    }

    fun resetRobotIdentity() {
        pollJob?.cancel()
        initJob?.cancel()
        ApiClient.resetRobotIdentity(getApplication())
        _uiState.update { it.copy(messages = emptyList()) }
        proactiveSinceId = 0L
        initSession(forceNewSession = true)
    }

    fun refreshSession() {
        pollJob?.cancel()
        initJob?.cancel()
        _uiState.update {
            it.copy(
                voiceId = ApiClient.getVoiceId(getApplication()),
                voiceLabel = TtsVoices.labelFor(ApiClient.getVoiceId(getApplication())),
            )
        }
        initSession()
    }

    private fun startProactivePolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(5000)
                val state = _uiState.value
                if (state.robotId.isBlank()) continue
                try {
                    val api = ApiClient.api(getApplication())
                    val resp = api.proactiveMessages(
                        robotId = state.robotId,
                        sessionId = state.sessionId,
                        sinceId = proactiveSinceId,
                    )
                    if (resp.items.isNotEmpty()) {
                        proactiveSinceId = resp.lastId
                        for (pm in resp.items) {
                            if (pm.content.isBlank()) continue
                            val msg = ChatMessage(
                                id = ++msgId,
                                role = "proactive",
                                text = pm.content,
                                expression = "neutral",
                            )
                            _uiState.update { it.copy(messages = it.messages + msg) }
                            val voiceId = ApiClient.getVoiceId(getApplication())
                            speakRobot(text = pm.content, serverAudio = null, voiceId = voiceId)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun updateInputText(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun toggleInputMode() {
        _uiState.update { state ->
            if (state.isRecording || state.conversationActive) return@update state
            val next = if (state.inputMode == InputMode.TEXT) InputMode.VOICE else InputMode.TEXT
            state.copy(inputMode = next)
        }
    }

    fun selectExpression(expr: FacialExpression) {
        _uiState.update { state ->
            val next = if (state.selectedExpression == expr) null else expr
            state.copy(selectedExpression = next)
        }
        if (_uiState.value.conversationActive) {
            updateLivePerception(facialExpression = _uiState.value.selectedExpression?.key)
        }
    }

    fun selectAction(action: BodyAction) {
        _uiState.update { state ->
            val next = if (state.selectedAction == action) null else action
            state.copy(selectedAction = next)
        }
    }

    fun selectRobotAction(action: RobotAction) {
        _uiState.update { state ->
            val next = if (state.selectedRobotAction == action) null else action
            state.copy(selectedRobotAction = next)
        }
    }

    fun selectGesture(gesture: RobotGesture) {
        _uiState.update { state ->
            val next = if (state.selectedGesture == gesture) null else gesture
            state.copy(selectedGesture = next)
        }
    }

    fun selectPosture(posture: RobotPosture) {
        _uiState.update { state ->
            val next = if (state.selectedPosture == posture) null else posture
            state.copy(selectedPosture = next)
        }
    }

    fun startRecording() {
        val ok = recorder.start()
        _uiState.update {
            it.copy(isRecording = ok, error = if (ok) null else "无法启动录音，请检查麦克风权限")
        }
    }

    fun stopRecordingAndSend() {
        if (!_uiState.value.isRecording) return
        val wav = recorder.stop()
        _uiState.update { it.copy(isRecording = false) }
        if (wav.size < 1000) {
            _uiState.update { it.copy(error = "录音太短") }
            return
        }
        sendMessage(text = "", audioWav = wav)
    }

    /** XBot 端侧：订阅对话生命周期（插话 / 拒听 / 相位），与 [conversationEvents] 等效。 */
    fun setConversationSessionListener(listener: ConversationSessionListener?) {
        conversationSessionListener = listener
    }

    fun clearConversationStatusHint() {
        _uiState.update { it.copy(conversationStatusHint = null) }
    }

    private fun emitConversationEvent(event: ConversationSessionEvent) {
        conversationSessionListener?.onConversationEvent(event)
        viewModelScope.launch {
            _conversationEvents.emit(event)
        }
    }

    private fun updateSessionPhase(
        phase: ConversationController.Phase,
        hint: String? = null,
        interruptKind: ConversationInterruptKind? = null,
    ) {
        val fsm = ConversationSessionFsm.fromPhase(phase)
        _uiState.update {
            it.copy(
                conversationPhase = phase,
                conversationActive = phase != ConversationController.Phase.IDLE,
                sessionFsmState = fsm,
                conversationStatusHint = hint ?: it.conversationStatusHint,
                lastInterruptKind = interruptKind ?: it.lastInterruptKind,
            )
        }
        emitConversationEvent(
            ConversationSessionEvent.PhaseChanged(phase = phase, fsmState = fsm),
        )
    }

    /** XBot 端侧：每帧/周期性更新当前身份与表情，随 utterance 一并上传。 */
    fun updateLivePerception(
        facialExpression: String? = null,
        identity: String? = null,
    ) {
        livePerception = livePerception.copy(
            facialExpression = facialExpression ?: livePerception.facialExpression,
            identity = identity ?: livePerception.identity,
        )
    }

    /** 对话模式：唤醒后进入聆听，流式 STT + 多轮轮流对话。 */
    fun toggleConversationMode() {
        val state = _uiState.value
        if (!state.speechEnabled) {
            _uiState.update { it.copy(error = "语音未启用，无法进入对话模式") }
            return
        }
        if (state.conversationActive) {
            exitConversationMode(reason = ConversationEndReason.USER_TOGGLE)
        } else {
            enterConversationMode()
        }
    }

    private fun enterConversationMode() {
        if (_uiState.value.conversationActive) return
        val app = getApplication<Application>()
        val baseUrl = ApiClient.getBaseUrl(app)
        livePerception = UtterancePerception(
            identity = ApiClient.getUserId(app)?.trim()?.ifBlank { null },
        )
        val controller = ConversationController(
            context = app,
            baseUrl = baseUrl,
            scope = viewModelScope,
            callbacks = object : ConversationController.Callbacks {
                override fun onPhase(phase: ConversationController.Phase) {
                    updateSessionPhase(phase)
                    _uiState.update { it.copy(error = null) }
                }

                override fun onPartial(text: String) {
                    _uiState.update { it.copy(partialTranscript = text) }
                }

                override fun onBargeIn() {
                    handleBargeIn()
                }

                override fun onConversationDismissed(text: String) {
                    handleConversationDismissed(dismissText = text)
                }

                override fun onFinal(text: String, voice: VoiceProsody?) {
                    handleConversationFinal(text, voice)
                }

                override fun onError(message: String) {
                    _uiState.update { it.copy(error = message) }
                }

                override fun onSessionEnded(reason: String, message: String) {
                    val endReason = if (reason == "conversation_idle") {
                        ConversationEndReason.SESSION_IDLE
                    } else {
                        ConversationEndReason.ERROR
                    }
                    val endMessage = if (reason == "conversation_idle") {
                        "对话已结束：$message"
                    } else {
                        message
                    }
                    exitConversationMode(
                        reason = endReason,
                        endMessage = endMessage,
                    )
                }
            },
        )
        conversationController = controller
        controller.start()
        emitConversationEvent(ConversationSessionEvent.Started())
        _uiState.update {
            it.copy(
                conversationActive = true,
                conversationPhase = ConversationController.Phase.LISTENING,
                sessionFsmState = ConversationSessionFsm.LISTENING,
                partialTranscript = "",
                lastInterruptKind = null,
                conversationStatusHint = null,
                error = null,
            )
        }
    }

    fun exitConversationMode(
        reason: ConversationEndReason = ConversationEndReason.USER_TOGGLE,
        userDismissed: Boolean = reason == ConversationEndReason.USER_DISMISSED,
        endMessage: String? = null,
    ) {
        val wasActive = _uiState.value.conversationActive
        conversationSendJob?.cancel()
        conversationSendJob = null
        cancelActiveSpeak()
        conversationController?.stop()
        conversationController = null
        livePerception = UtterancePerception()
        _uiState.update {
            it.copy(
                conversationActive = false,
                conversationPhase = ConversationController.Phase.IDLE,
                sessionFsmState = ConversationSessionFsm.IDLE,
                partialTranscript = "",
                isSpeaking = false,
                error = when {
                    userDismissed -> null
                    reason == ConversationEndReason.SESSION_IDLE -> endMessage ?: "对话已结束"
                    else -> it.error
                },
            )
        }
        if (wasActive) {
            emitConversationEvent(
                ConversationSessionEvent.Ended(reason = reason, message = endMessage),
            )
        }
    }

    private fun handleConversationDismissed(dismissText: String = "") {
        handleBargeIn()
        _uiState.update {
            it.copy(
                lastInterruptKind = ConversationInterruptKind.DISMISS,
                conversationStatusHint = "已结束对话，等你下次唤醒",
            )
        }
        emitConversationEvent(ConversationSessionEvent.Dismissed(text = dismissText))
        exitConversationMode(
            reason = ConversationEndReason.USER_DISMISSED,
            userDismissed = true,
        )
    }

    private fun handleBargeIn() {
        speakGeneration++
        conversationSendJob?.cancel()
        conversationSendJob = null
        cancelActiveSpeak()
        _uiState.update {
            it.copy(
                isSpeaking = false,
                pendingMessageId = null,
                partialTranscript = "",
                lastInterruptKind = ConversationInterruptKind.BARGE_IN,
                conversationStatusHint = "已停下，请说…",
                sessionFsmState = ConversationSessionFsm.LISTENING,
            )
        }
        emitConversationEvent(ConversationSessionEvent.BargeIn)
    }

    private fun cancelActiveSpeak() {
        activeSpeakJob?.cancel()
        activeSpeakJob = null
        speaker.stop()
    }

    private fun buildConversationPerception(sttVoice: VoiceProsody?): PerceptionInput? {
        val app = getApplication<Application>()
        val expr = livePerception.facialExpression
            ?.let { FacialExpression.fromKey(it) }
            ?: _uiState.value.selectedExpression
        val identity = livePerception.identity?.trim()?.ifBlank { null }
            ?: ApiClient.getUserId(app)?.trim()?.ifBlank { null }
        return perceptionFor(
            expression = expr,
            action = null,
            robotAction = null,
            gesture = null,
            posture = null,
            identity = identity,
            sttVoice = sttVoice,
        )
    }

    private fun handleConversationFinal(text: String, voice: VoiceProsody?) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            conversationController?.resumeListening()
            return
        }
        if (ConversationDismissDetector.matches(trimmed)) {
            handleConversationDismissed(dismissText = trimmed)
            return
        }
        conversationController?.setThinking()
        _uiState.update {
            it.copy(
                partialTranscript = trimmed,
                lastInterruptKind = null,
                conversationStatusHint = null,
            )
        }
        val perception = buildConversationPerception(voice)
        val expr = livePerception.facialExpression
            ?.let { FacialExpression.fromKey(it) }
            ?: _uiState.value.selectedExpression
        val identity = livePerception.identity
            ?: ApiClient.getUserId(getApplication())?.trim()?.ifBlank { null }
        sendConversationMessage(
            text = trimmed,
            perception = perception,
            displayExpression = expr,
            displayIdentity = identity,
            sttVoice = voice,
        )
    }

    private fun sendConversationMessage(
        text: String,
        perception: PerceptionInput?,
        displayExpression: FacialExpression?,
        displayIdentity: String?,
        sttVoice: VoiceProsody?,
    ) {
        val state = _uiState.value
        if (state.robotId.isBlank()) return

        val displayText = buildUserDisplay(
            text = text,
            expression = displayExpression,
            action = null,
            robotAction = null,
            gesture = null,
            posture = null,
            identity = displayIdentity,
            isVoice = true,
        )
        val userMsgId = ++msgId
        _uiState.update {
            it.copy(
                messages = it.messages + ChatMessage(
                    id = userMsgId,
                    role = "user",
                    text = displayText,
                    expression = displayExpression?.key,
                ),
                pendingMessageId = userMsgId,
                error = null,
            )
        }

        conversationSendJob?.cancel()
        val speakGen = speakGeneration
        conversationSendJob = viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                withContext(Dispatchers.Main) {
                    AudioRouteHelper.prepareForPlayback(app)
                }
                val userId = displayIdentity ?: ApiClient.getUserId(app)
                val voiceId = ApiClient.getVoiceId(app)
                val req = ChatRequest(
                    robotId = state.robotId,
                    userId = userId,
                    sessionId = state.sessionId,
                    input = ChatInput(
                        text = text,
                        audio = null,
                        perception = perception,
                        voiceId = voiceId,
                        skipTts = true,
                    ),
                )
                if (speakGen != speakGeneration) return@launch
                conversationController?.setSpeaking()
                val resp = ChatStreamClient(ApiClient.getBaseUrl(app)).stream(req) { chunk ->
                    if (speakGen != speakGeneration) return@stream
                    ChatStreamClient.logSpeak(chunk)
                    speakChunk(chunk, voiceId = voiceId, generation = speakGen)
                }
                if (speakGen != speakGeneration) return@launch
                ApiClient.saveSessionId(app, resp.sessionId)
                val output = resp.output
                val hasReply = output.text.isNotBlank()
                _uiState.update {
                    val newMessages = if (hasReply) {
                        it.messages + ChatMessage(
                            id = ++msgId,
                            role = "assistant",
                            text = output.text,
                            expression = output.facialExpression,
                            stateLabel = stateLabelFor(output),
                            voiceLabel = voiceLabel(output.voice),
                            timbreLabel = TtsVoices.labelFor(voiceId),
                        )
                    } else {
                        it.messages
                    }
                    it.copy(
                        sessionId = resp.sessionId,
                        messages = newMessages,
                        pendingMessageId = null,
                        error = null,
                    )
                }
            } catch (_: CancellationException) {
                // barge-in 打断，静默忽略
            } catch (e: Exception) {
                if (speakGen == speakGeneration) {
                    _uiState.update {
                        it.copy(pendingMessageId = null, error = "发送失败：${e.message}")
                    }
                }
            } finally {
                if (speakGen == speakGeneration &&
                    _uiState.value.conversationActive
                ) {
                    _uiState.update { it.copy(partialTranscript = "") }
                    conversationController?.resumeListening()
                }
            }
        }
    }

    /** 发送：有文字发文字，有感知信号则附带，仅感知信号也可单独发送。 */
    fun send() {
        val state = _uiState.value
        val text = state.inputText.trim()
        if (text.isBlank() && !hasPerceptionSelection(state)) return
        sendMessage(text = text, audioWav = null)
    }

    private fun hasPerceptionSelection(state: ChatUiState): Boolean =
        state.selectedExpression != null ||
            state.selectedAction != null ||
            state.selectedRobotAction != null ||
            state.selectedGesture != null ||
            state.selectedPosture != null

    private fun buildUserDisplay(
        text: String,
        expression: FacialExpression?,
        action: BodyAction?,
        robotAction: RobotAction?,
        gesture: RobotGesture?,
        posture: RobotPosture?,
        identity: String? = null,
        isVoice: Boolean,
    ): String {
        val tags = buildList {
            identity?.trim()?.takeIf { it.isNotBlank() }?.let { add("👤$it") }
            expression?.let { add("${it.emoji}${it.label}") }
            action?.let { add("${it.emoji}${it.label}") }
            robotAction?.let { add("${it.emoji}${it.label}") }
            gesture?.let { add("${it.emoji}${it.label}") }
            posture?.let { add("${it.emoji}${it.label}") }
        }
        val tagLine = tags.joinToString(" ")
        return when {
            isVoice && tagLine.isNotBlank() -> "$tagLine + 🎤 语音"
            isVoice -> "🎤 语音消息"
            tagLine.isNotBlank() && text.isNotBlank() -> "$tagLine\n$text"
            tagLine.isNotBlank() -> tagLine
            else -> text
        }
    }

    private fun perceptionFor(
        expression: FacialExpression?,
        action: BodyAction?,
        robotAction: RobotAction?,
        gesture: RobotGesture?,
        posture: RobotPosture?,
        identity: String?,
        sttVoice: VoiceProsody? = null,
    ): PerceptionInput? {
        if (expression == null && action == null && robotAction == null &&
            gesture == null && posture == null && identity.isNullOrBlank() && sttVoice == null
        ) {
            return null
        }
        val gesturePayload = when {
            robotAction != null -> GestureAction(
                type = robotAction.key,
                params = mapOf(
                    "target" to "robot",
                    "event" to if (robotAction == RobotAction.WAKE) {
                        "phone_docked"
                    } else {
                        "phone_undocked"
                    },
                ),
            )
            gesture != null -> GestureAction(type = gesture.key)
            else -> null
        }
        return PerceptionInput(
            facialExpression = expression?.key,
            touch = action?.label,
            identity = identity?.trim()?.ifBlank { null },
            gesture = gesturePayload,
            posture = posture?.let { PostureAction(type = it.key) },
            voice = sttVoice,
        )
    }

    private fun voiceLabel(voice: VoiceProsody?): String? {
        if (voice == null) return null
        val parts = listOfNotNull(voice.tone, voice.intonation, voice.speed)
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    /** 机器人回复携带的 FSM 互动状态（9 态之一）→ 可读标签。 */
    private fun stateLabelFor(output: RobotOutput): String? {
        val state = RobotState.fromKey(output.robotState) ?: return output.robotStateLabel
        return "${state.emoji}${state.label}"
    }

    private suspend fun speakChunk(
        text: String,
        voice: VoiceProsody? = null,
        voiceId: String = ApiClient.getVoiceId(getApplication()),
        generation: Int = speakGeneration,
    ) {
        if (generation != speakGeneration) return
        speakMutex.withLock {
            if (generation != speakGeneration) return
            speaker.speak(
                text = text,
                serverAudio = null,
                voice = voice,
                voiceId = voiceId,
                onStart = {
                    if (generation == speakGeneration) {
                        _uiState.update { it.copy(isSpeaking = true) }
                    }
                },
                onComplete = {
                    if (generation == speakGeneration) {
                        _uiState.update { it.copy(isSpeaking = false) }
                    }
                },
            )
        }
    }

    private fun speakRobot(
        text: String,
        serverAudio: AudioPayload? = null,
        voice: VoiceProsody? = null,
        voiceId: String = ApiClient.getVoiceId(getApplication()),
    ) {
        viewModelScope.launch {
            if (serverAudio != null && serverAudio.data.isNotBlank()) {
                speakMutex.withLock {
                    speaker.speak(
                        text = text,
                        serverAudio = serverAudio,
                        voice = voice,
                        voiceId = voiceId,
                        onStart = { _uiState.update { it.copy(isSpeaking = true) } },
                        onComplete = { _uiState.update { it.copy(isSpeaking = false) } },
                    )
                }
            } else {
                speakChunk(text, voice, voiceId)
            }
        }
    }

    private fun speakOutput(output: RobotOutput, voiceId: String) {
        speakRobot(
            text = output.text,
            serverAudio = null,
            voice = output.voice,
            voiceId = voiceId,
        )
    }

    private fun sendMessage(
        text: String,
        audioWav: ByteArray?,
        sttVoice: VoiceProsody? = null,
        isConversation: Boolean = false,
    ) {
        val state = _uiState.value
        if (state.robotId.isBlank()) return

        val expression = state.selectedExpression
        val action = state.selectedAction
        val robotAction = state.selectedRobotAction
        val gesture = state.selectedGesture
        val posture = state.selectedPosture
        val hasText = text.isNotBlank()
        val hasAudio = audioWav != null
        val hasPerception = hasPerceptionSelection(state)
        if (!hasText && !hasAudio && !hasPerception) return

        val isVoice = hasAudio || (isConversation && text.isNotBlank())
        val displayText = buildUserDisplay(
            text, expression, action, robotAction, gesture, posture,
            identity = null,
            isVoice = isVoice,
        )

        val userMsgId = ++msgId
        _uiState.update {
            it.copy(
                messages = it.messages + ChatMessage(
                    id = userMsgId,
                    role = "user",
                    text = displayText,
                    expression = expression?.key,
                ),
                inputText = if (hasText && !isVoice) "" else it.inputText,
                selectedExpression = null,
                selectedAction = null,
                selectedRobotAction = null,
                selectedGesture = null,
                selectedPosture = null,
                pendingMessageId = userMsgId,
                error = null,
            )
        }

        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                if (state.speechEnabled) {
                    withContext(Dispatchers.Main) {
                        AudioRouteHelper.prepareForPlayback(app)
                    }
                }
                val api = ApiClient.api(app)
                val audio = audioWav?.let {
                    AudioPayload(
                        format = "wav",
                        encoding = "base64",
                        sampleRate = 16000,
                        data = Base64.encodeToString(it, Base64.NO_WRAP),
                    )
                }
                val userId = ApiClient.getUserId(app)
                val perception = perceptionFor(
                    expression, action, robotAction, gesture, posture, userId, sttVoice,
                )
                val voiceId = ApiClient.getVoiceId(app)
                val req = ChatRequest(
                    robotId = state.robotId,
                    userId = userId,
                    sessionId = state.sessionId,
                    input = ChatInput(
                        text = text,
                        audio = audio,
                        perception = perception,
                        voiceId = voiceId,
                        skipTts = true,
                    ),
                )
                val resp = if (state.speechEnabled) {
                    if (isConversation) {
                        conversationController?.setSpeaking()
                    }
                    ChatStreamClient(ApiClient.getBaseUrl(app)).stream(req) { chunk ->
                        ChatStreamClient.logSpeak(chunk)
                        speakChunk(chunk, voiceId = voiceId)
                    }
                } else {
                    api.chat(req)
                }
                ApiClient.saveSessionId(app, resp.sessionId)
                val output = resp.output
                val hasReply = output.text.isNotBlank()
                _uiState.update {
                    val newMessages = if (hasReply) {
                        it.messages + ChatMessage(
                            id = ++msgId,
                            role = "assistant",
                            text = output.text,
                            expression = output.facialExpression,
                            stateLabel = stateLabelFor(output),
                            voiceLabel = voiceLabel(output.voice),
                            timbreLabel = TtsVoices.labelFor(voiceId),
                        )
                    } else {
                        it.messages
                    }
                    it.copy(
                        sessionId = resp.sessionId,
                        messages = newMessages,
                        pendingMessageId = null,
                        error = if (!hasReply && isVoice) {
                            "未能识别语音"
                        } else {
                            null
                        },
                    )
                }
                if (hasReply && !state.speechEnabled) {
                    speakOutput(output, voiceId)
                }
                if (isConversation && _uiState.value.conversationActive) {
                    _uiState.update { it.copy(partialTranscript = "") }
                    conversationController?.resumeListening()
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(pendingMessageId = null, error = "发送失败：${e.message}")
                }
                if (isConversation && _uiState.value.conversationActive) {
                    conversationController?.resumeListening()
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
        initJob?.cancel()
        exitConversationMode()
        speaker.shutdown()
    }
}
