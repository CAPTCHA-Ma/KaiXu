package com.kaixu.desktop.presentation.ui.scheduling

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kaixu.desktop.presentation.ui.workspaces.localMoment

internal data class ReadOnlyOccurrenceDetails(
    val title: String,
    val description: String?,
    val startsAt: Long,
    val endsAt: Long,
    val workspaceName: String,
    val weekdays: Set<Int>?,
    val repeatUntil: String?,
    val reminderOffsetMinutes: Int?,
)

@Composable
internal fun ReadOnlyOccurrenceDialog(details: ReadOnlyOccurrenceDetails, tag: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(details.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${localMoment(details.startsAt)} — ${localMoment(details.endsAt)}")
                details.description?.takeIf { it.isNotBlank() }?.let { Text("说明：$it") }
                Text("所属工作区：${details.workspaceName}")
                details.weekdays?.let { days ->
                    Text("重复：" + days.sorted().joinToString("、") { "周" + "一二三四五六日"[it - 1] } +
                        (details.repeatUntil?.let { "，至 $it" } ?: ""))
                }
                details.reminderOffsetMinutes?.let { Text("站内提醒：提前 $it 分钟") }
                Text("该课次只读。", modifier = Modifier.testTag(tag))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
