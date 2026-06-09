package com.pophie.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
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
import com.pophie.app.data.model.BodyAction
import com.pophie.app.data.model.FacialExpression
import com.pophie.app.data.model.RobotAction
import com.pophie.app.data.model.RobotGesture
import com.pophie.app.data.model.RobotPosture

private val tabs = listOf("表情", "动作", "手势", "姿态")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpressionPicker(
    selectedExpression: FacialExpression?,
    selectedAction: BodyAction?,
    selectedRobotAction: RobotAction?,
    selectedGesture: RobotGesture?,
    selectedPosture: RobotPosture?,
    onSelectExpression: (FacialExpression) -> Unit,
    onSelectAction: (BodyAction) -> Unit,
    onSelectRobotAction: (RobotAction) -> Unit,
    onSelectGesture: (RobotGesture) -> Unit,
    onSelectPosture: (RobotPosture) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }

    val summary = buildList {
        selectedExpression?.let { add("${it.emoji}${it.label}") }
        selectedAction?.let { add("${it.emoji}${it.label}") }
        selectedRobotAction?.let { add("${it.emoji}${it.label}") }
        selectedGesture?.let { add("${it.emoji}${it.label}") }
        selectedPosture?.let { add("${it.emoji}${it.label}") }
    }.joinToString(" · ")

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "感知信号",
                style = MaterialTheme.typography.labelLarge,
            )
            if (summary.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column {
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    edgePadding = 0.dp,
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            enabled = enabled,
                            text = { Text(title) },
                        )
                    }
                }

                when (selectedTab) {
                    0 -> ChipGrid(
                        items = FacialExpression.entries,
                        selected = selectedExpression,
                        onSelect = onSelectExpression,
                        label = { "${it.emoji}${it.label}" },
                        enabled = enabled,
                    )
                    1 -> Column {
                        Text(
                            text = "用户动作",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        ChipGrid(
                            items = BodyAction.entries,
                            selected = selectedAction,
                            onSelect = onSelectAction,
                            label = { "${it.emoji}${it.label}" },
                            enabled = enabled,
                        )
                        Text(
                            text = "机器人",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        ChipGrid(
                            items = RobotAction.entries,
                            selected = selectedRobotAction,
                            onSelect = onSelectRobotAction,
                            label = { "${it.emoji}${it.label}" },
                            enabled = enabled,
                        )
                    }
                    2 -> ChipGrid(
                        items = RobotGesture.entries,
                        selected = selectedGesture,
                        onSelect = onSelectGesture,
                        label = { "${it.emoji}${it.label}" },
                        enabled = enabled,
                    )
                    3 -> ChipGrid(
                        items = RobotPosture.entries,
                        selected = selectedPosture,
                        onSelect = onSelectPosture,
                        label = { "${it.emoji}${it.label}" },
                        enabled = enabled,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipGrid(
    items: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { item ->
            FilterChip(
                selected = selected == item,
                onClick = { onSelect(item) },
                label = { Text(label(item)) },
                enabled = enabled,
            )
        }
    }
}
