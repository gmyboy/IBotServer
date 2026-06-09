package com.pophie.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pophie.app.data.model.OwnerProfile

private val genderOptions = listOf(
    null to "不填写",
    "male" to "男",
    "female" to "女",
    "other" to "其他",
)

@Composable
fun ActivationWizardScreen(
    onComplete: (OwnerProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by remember { mutableIntStateOf(0) }
    var nickname by remember { mutableStateOf("") }
    var robotName by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf<String?>(null) }
    var birthday by remember { mutableStateOf("") }
    var faceRegistered by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "首次激活",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (step) {
                0 -> "告诉我怎么称呼你？"
                1 -> "给我起个名字吧"
                else -> "补充信息（可选）"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        when (step) {
            0 -> {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("主人昵称") },
                    placeholder = { Text("例如：小明") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            1 -> {
                OutlinedTextField(
                    value = robotName,
                    onValueChange = { robotName = it },
                    label = { Text("机器人名字") },
                    placeholder = { Text("例如：狗蛋") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            else -> {
                Text("性别", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    genderOptions.forEach { (value, label) ->
                        FilterChip(
                            selected = gender == value,
                            onClick = { gender = value },
                            label = { Text(label) },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = birthday,
                    onValueChange = { birthday = it },
                    label = { Text("生日（YYYY-MM-DD，可留空）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(
                        checked = faceRegistered,
                        onCheckedChange = { faceRegistered = it },
                    )
                    Text("已录入主人人脸（端侧标志，不上传图像）")
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        Button(
            onClick = {
                when (step) {
                    0 -> if (nickname.trim().isNotEmpty()) step = 1
                    1 -> if (robotName.trim().isNotEmpty()) step = 2
                    else -> {
                        val bday = birthday.trim().ifBlank { null }
                        onComplete(
                            OwnerProfile(
                                nickname = nickname.trim(),
                                robotName = robotName.trim(),
                                gender = gender,
                                birthday = bday,
                                faceRegistered = faceRegistered,
                            ),
                        )
                    }
                }
            },
            enabled = when (step) {
                0 -> nickname.trim().isNotEmpty()
                1 -> robotName.trim().isNotEmpty()
                else -> true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (step < 2) "下一步" else "完成激活")
        }
    }
}
