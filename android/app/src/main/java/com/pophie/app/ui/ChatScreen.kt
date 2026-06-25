package com.pophie.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxSize
import com.pophie.app.audio.ConversationController
import com.pophie.app.data.model.FacialExpression
import com.pophie.app.viewmodel.ChatMessage
import com.pophie.app.viewmodel.ChatViewModel
import com.pophie.app.viewmodel.InputMode

private val InputBarBg = Color(0xFF1A1A24)
private val InputFieldBg = Color(0xFF2A2A38)
private val InputFieldBgFocused = Color(0xFF32324A)
private val Accent = Color(0xFF6C63FF)
private val AccentDim = Color(0xFF4E47B8)
private val InputBarHeight = 48.dp
private val InputBarCorner = 24.dp
private val InputBarHorizontalPadding = 12.dp
private val InputBarVerticalPadding = 8.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val sending = state.pendingMessageId != null
    val canSend = !sending &&
        (state.inputText.isNotBlank() ||
            state.selectedExpression != null ||
            state.selectedAction != null ||
            state.selectedRobotAction != null ||
            state.selectedGesture != null ||
            state.selectedPosture != null)

    LaunchedEffect(state.messages.size, state.pendingMessageId) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Pophie")
                        Text(
                            text = state.sessionLabel,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (state.isSpeaking) {
                            Text(
                                text = "🔊 Pophie 正在说话…",
                                style = MaterialTheme.typography.labelSmall,
                                color = Accent,
                            )
                        } else if (state.conversationActive) {
                            val phaseLabel = when (state.conversationPhase) {
                                com.pophie.app.audio.ConversationController.Phase.LISTENING -> "🎧 对话中 · 聆听"
                                com.pophie.app.audio.ConversationController.Phase.THINKING -> "🎧 对话中 · 思考"
                                com.pophie.app.audio.ConversationController.Phase.SPEAKING -> "🎧 对话中 · 说话"
                                else -> "🎧 对话中"
                            }
                            Text(
                                text = phaseLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = Accent,
                            )
                        } else if (state.voiceLabel.isNotBlank()) {
                            Text(
                                text = "音色：${state.voiceLabel}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF888888),
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            if (state.error != null) {
                Text(
                    text = state.error!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.messages, key = { it.id }) { msg ->
                    MessageBubble(
                        msg = msg,
                        isPending = msg.id == state.pendingMessageId,
                    )
                }
            }

            ExpressionPicker(
                selectedExpression = state.selectedExpression,
                selectedAction = state.selectedAction,
                selectedRobotAction = state.selectedRobotAction,
                selectedGesture = state.selectedGesture,
                selectedPosture = state.selectedPosture,
                onSelectExpression = viewModel::selectExpression,
                onSelectAction = viewModel::selectAction,
                onSelectRobotAction = viewModel::selectRobotAction,
                onSelectGesture = viewModel::selectGesture,
                onSelectPosture = viewModel::selectPosture,
                enabled = !sending && !state.conversationActive,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )

            ChatInputBar(
                inputMode = state.inputMode,
                inputText = state.inputText,
                conversationActive = state.conversationActive,
                conversationPhase = state.conversationPhase,
                partialTranscript = state.partialTranscript,
                sending = sending,
                canSend = canSend,
                onInputChange = viewModel::updateInputText,
                onToggleMode = viewModel::toggleInputMode,
                onSend = viewModel::send,
                onToggleConversation = viewModel::toggleConversationMode,
            )
        }
    }
}

@Composable
private fun ChatInputBar(
    inputMode: InputMode,
    inputText: String,
    conversationActive: Boolean,
    conversationPhase: ConversationController.Phase,
    partialTranscript: String,
    sending: Boolean,
    canSend: Boolean,
    onInputChange: (String) -> Unit,
    onToggleMode: () -> Unit,
    onSend: () -> Unit,
    onToggleConversation: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(InputBarBg)
            .padding(
                horizontal = InputBarHorizontalPadding,
                vertical = InputBarVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            onClick = onToggleMode,
            enabled = !sending && !conversationActive,
            modifier = Modifier
                .size(InputBarHeight)
                .clip(CircleShape)
                .background(InputFieldBg),
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = Accent,
                disabledContentColor = Color(0xFF666680),
            ),
        ) {
            Icon(
                imageVector = if (inputMode == InputMode.TEXT) Icons.Default.Mic else Icons.Default.Keyboard,
                contentDescription = if (inputMode == InputMode.TEXT) "切换到语音" else "切换到文字",
                modifier = Modifier.size(22.dp),
            )
        }

        when (inputMode) {
            InputMode.TEXT -> ChatTextField(
                value = inputText,
                onValueChange = onInputChange,
                enabled = !sending,
                modifier = Modifier
                    .weight(1f)
                    .height(InputBarHeight),
            )
            InputMode.VOICE -> ConversationModeButton(
                active = conversationActive,
                phase = conversationPhase,
                partialTranscript = partialTranscript,
                enabled = !sending,
                onToggle = onToggleConversation,
                modifier = Modifier
                    .weight(1f)
                    .height(InputBarHeight),
            )
        }

        FilledIconButton(
            onClick = onSend,
            enabled = canSend && !conversationActive,
            modifier = Modifier.size(InputBarHeight),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Accent,
                contentColor = Color.White,
                disabledContainerColor = AccentDim.copy(alpha = 0.35f),
                disabledContentColor = Color.White.copy(alpha = 0.45f),
            ),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "发送",
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun InputSlotContainer(
    background: Color,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.CenterStart,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(InputBarCorner))
            .background(background)
            .padding(horizontal = 16.dp),
        contentAlignment = contentAlignment,
    ) {
        content()
    }
}

@Composable
private fun ChatTextField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    InputSlotContainer(
        background = if (enabled) InputFieldBg else InputFieldBg.copy(alpha = 0.6f),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                Text(
                    text = "输入消息…",
                    color = Color(0xFF7A7A90),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                textStyle = TextStyle(
                    color = if (enabled) Color.White else Color.White.copy(alpha = 0.5f),
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                ),
                singleLine = true,
                cursorBrush = SolidColor(Accent),
            )
        }
    }
}

@Composable
private fun ConversationModeButton(
    active: Boolean,
    phase: ConversationController.Phase,
    partialTranscript: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = when {
        !enabled -> InputFieldBg.copy(alpha = 0.5f)
        active -> Accent
        else -> InputFieldBgFocused
    }
    val label = when {
        !active -> "点击开始对话"
        phase == ConversationController.Phase.THINKING -> "思考中…"
        phase == ConversationController.Phase.SPEAKING -> "正在回复…"
        partialTranscript.isNotBlank() -> partialTranscript
        else -> "聆听中…"
    }
    InputSlotContainer(
        background = bg,
        contentAlignment = Alignment.Center,
        modifier = modifier.then(
            if (enabled) Modifier.pointerInput(Unit) {
                detectTapGestures(onTap = { onToggle() })
            } else Modifier,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (active && phase == ConversationController.Phase.LISTENING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
                Spacer(Modifier.width(8.dp))
            } else if (!active) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label,
                color = if (enabled) Color.White else Color(0xFF7A7A90),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage, isPending: Boolean) {
    val isUser = msg.role == "user"
    val bg = if (isUser) Accent else Color(0xFF2A2A3A)
    val align = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val expr = msg.expression?.let { FacialExpression.fromKey(it) }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = align) {
        Column(
            modifier = Modifier
                .background(bg, RoundedCornerShape(12.dp))
                .padding(12.dp)
                .fillMaxWidth(0.85f),
        ) {
            if (!isUser && expr != null) {
                Text("${expr.emoji} ${expr.label}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFCCCCCC))
                Spacer(Modifier.height(4.dp))
            }
            if (!isUser && msg.stateLabel != null) {
                Text("🎭 ${msg.stateLabel}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFBBBBCC))
                Spacer(Modifier.height(4.dp))
            }
            if (!isUser && msg.timbreLabel != null) {
                Text("🔊 ${msg.timbreLabel}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF999999))
                Spacer(Modifier.height(4.dp))
            }
            if (!isUser && msg.voiceLabel != null) {
                Text("🎙 ${msg.voiceLabel}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFAAAAAA))
                Spacer(Modifier.height(4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isUser && isPending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(msg.text, color = Color.White)
            }
        }
    }
}
