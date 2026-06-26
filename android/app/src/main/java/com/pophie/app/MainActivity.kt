package com.pophie.app

import android.Manifest
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import android.util.Log
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pophie.app.audio.ConversationSessionEvent
import com.pophie.app.audio.ConversationSessionListener
import com.pophie.app.data.ApiClient
import com.pophie.app.ui.ActivationWizardScreen
import com.pophie.app.ui.ChatScreen
import com.pophie.app.ui.SettingsScreen
import com.pophie.app.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val tag = "PophieSession"

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* handled in UI */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissionLauncher.launch(perms.toTypedArray())

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF6C63FF),
                    background = Color(0xFF121218),
                    surface = Color(0xFF1E1E28),
                ),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var showSettings by remember { mutableStateOf(false) }
                    var activated by remember {
                        mutableStateOf(ApiClient.isActivated(this@MainActivity))
                    }
                    val chatViewModel: ChatViewModel = viewModel()

                    // XBot 接入示例：订阅对话生命周期（插话 / 拒听 / FSM 相位）
                    LaunchedEffect(chatViewModel) {
                        chatViewModel.setConversationSessionListener(
                            object : ConversationSessionListener {
                                override fun onConversationEvent(event: ConversationSessionEvent) {
                                    when (event) {
                                        is ConversationSessionEvent.Started ->
                                            Log.i(tag, "conversation started fsm=${event.fsmState}")
                                        is ConversationSessionEvent.PhaseChanged ->
                                            Log.i(tag, "phase=${event.phase} fsm=${event.fsmState}")
                                        ConversationSessionEvent.BargeIn ->
                                            Log.i(tag, "barge-in → listening (停播，继续聆听)")
                                        is ConversationSessionEvent.Dismissed ->
                                            Log.i(tag, "dismiss text=${event.text} → idle (待机)")
                                        is ConversationSessionEvent.Ended ->
                                            Log.i(tag, "ended reason=${event.reason}")
                                    }
                                    // XBot：在此根据 event 切换虚拟宠物 FSM / 关麦 / 动画
                                }
                            },
                        )
                    }

                    val uiState by chatViewModel.uiState.collectAsState()

                    when {
                        !activated -> {
                            ActivationWizardScreen(
                                onComplete = { owner ->
                                    ApiClient.saveOwnerProfile(this@MainActivity, owner)
                                    activated = true
                                    chatViewModel.onActivationComplete()
                                },
                            )
                        }
                        showSettings -> {
                            SettingsScreen(
                                currentUrl = ApiClient.getBaseUrl(this),
                                currentVoiceId = ApiClient.getVoiceId(this),
                                currentUserId = ApiClient.getUserId(this) ?: "",
                                robotId = uiState.robotId.ifBlank {
                                    ApiClient.getRobotId(this) ?: ""
                                },
                                ownerProfile = ApiClient.getOwnerProfile(this),
                                onSave = { url, voiceId, userId ->
                                    ApiClient.saveBaseUrl(this, url)
                                    ApiClient.saveVoiceId(this, voiceId)
                                    ApiClient.saveUserId(this, userId)
                                    chatViewModel.refreshSession()
                                    showSettings = false
                                },
                                onNewConversation = {
                                    chatViewModel.startNewConversation()
                                    showSettings = false
                                },
                                onResetRobotIdentity = {
                                    chatViewModel.resetRobotIdentity()
                                    showSettings = false
                                },
                                onResetOwner = {
                                    lifecycleScope.launch {
                                        ApiClient.deleteOwnerRemote(this@MainActivity)
                                    }
                                    ApiClient.clearOwnerProfile(this)
                                    activated = false
                                    chatViewModel.resetRobotIdentity()
                                    showSettings = false
                                },
                                onBack = { showSettings = false },
                            )
                        }
                        else -> {
                            ChatScreen(
                                viewModel = chatViewModel,
                                onOpenSettings = { showSettings = true },
                            )
                        }
                    }
                }
            }
        }
    }
}
