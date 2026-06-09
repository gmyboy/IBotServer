package com.pophie.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pophie.app.data.model.OwnerProfile
import com.pophie.app.data.model.TtsVoices

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    currentUrl: String,
    currentVoiceId: String,
    currentUserId: String,
    robotId: String,
    ownerProfile: OwnerProfile? = null,
    onSave: (url: String, voiceId: String, userId: String) -> Unit,
    onNewConversation: () -> Unit,
    onResetRobotIdentity: () -> Unit,
    onResetOwner: () -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by remember(currentUrl) { mutableStateOf(currentUrl) }
    var voiceId by remember(currentVoiceId) { mutableStateOf(currentVoiceId) }
    var userId by remember(currentUserId) { mutableStateOf(currentUserId) }

    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("设置")
        Spacer(Modifier.height(8.dp))
        if (ownerProfile != null) {
            Text("主人档案")
            Text(
                text = "称呼：${ownerProfile.nickname} · 机器人：${ownerProfile.robotName}",
                modifier = Modifier.padding(vertical = 4.dp),
            )
            OutlinedButton(
                onClick = onResetOwner,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("重新设置主人")
            }
            Spacer(Modifier.height(16.dp))
        }
        Text("机器人身份")
        Text(
            text = robotId.ifBlank { "—" },
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        OutlinedButton(
            onClick = onNewConversation,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("新对话（保留记忆）")
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = onResetRobotIdentity,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("重置机器人身份")
        }
        Spacer(Modifier.height(16.dp))
        Text("当前用户（模拟身份）")
        OutlinedTextField(
            value = userId,
            onValueChange = { userId = it },
            label = { Text("身份/人名，留空=未识别") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(16.dp))
        Text("服务器地址")
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Base URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(16.dp))
        Text("机器人音色")
        Spacer(Modifier.height(6.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TtsVoices.presets.forEach { option ->
                FilterChip(
                    selected = voiceId == option.id,
                    onClick = { voiceId = option.id },
                    label = { Text(option.label) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onSave(url, voiceId, userId) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存")
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("返回")
        }
    }
}
