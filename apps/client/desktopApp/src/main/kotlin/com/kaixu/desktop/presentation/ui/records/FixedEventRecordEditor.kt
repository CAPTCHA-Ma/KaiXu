package com.kaixu.desktop.presentation.ui.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.desktop.presentation.ui.workspaces.localMoment
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.usecase.scheduling.EditLocalFixedEventUseCase
import com.kaixu.domain.usecase.scheduling.FixedEventEditConfirmation
import com.kaixu.domain.usecase.scheduling.FixedEventEditResult
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val EventTimeZone = ZoneId.of("Asia/Shanghai")
private val EventTimeFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)

internal fun formatEventEditTime(millis: Long): String =
    EventTimeFormat.format(Instant.ofEpochMilli(millis).atZone(EventTimeZone))

/** 未改动显示值时沿用原毫秒值，避免只编辑标题却截断事件的秒或毫秒。 */
internal fun parseEventEditTime(text: String, originalMillis: Long): Long {
    val value = text.trim()
    if (value == formatEventEditTime(originalMillis)) return originalMillis
    require(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}").matches(value)) {
        "请按 yyyy-MM-dd HH:mm 输入日期和时间。"
    }
    try {
        val dateTime = LocalDateTime.parse(value, EventTimeFormat)
        require(dateTime.year in 1..9999) { "请输入有效年份。" }
        val offsets = EventTimeZone.rules.getValidOffsets(dateTime)
        require(offsets.size == 1) { "该时间在上海时区不存在或不唯一，请重新选择。" }
        return dateTime.toInstant(offsets.single()).toEpochMilli()
    } catch (_: DateTimeException) {
        throw IllegalArgumentException("日期或时间无效，请检查月份、日期及小时分钟。")
    }
}

@Composable
internal fun FixedEventRecordEditor(
    event: FixedEvent,
    sources: LocalDataSources,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val original = remember(event.id) { event }
    var title by remember(event.id) { mutableStateOf(event.title) }
    var description by remember(event.id) { mutableStateOf(event.description.orEmpty()) }
    var start by remember(event.id) { mutableStateOf(formatEventEditTime(event.startsAt)) }
    var end by remember(event.id) { mutableStateOf(formatEventEditTime(event.endsAt)) }
    var error by remember(event.id) { mutableStateOf<String?>(null) }
    var saving by remember(event.id) { mutableStateOf(false) }
    var confirmation by remember(event.id) { mutableStateOf<FixedEventEditConfirmation?>(null) }
    val scope = rememberCoroutineScope()
    val useCase = remember(sources) {
        EditLocalFixedEventUseCase(sources.fixedEvents, sources.workSessions, sources.workspaces, LOCAL_ACTOR_ID) { block ->
            sources.writeTransaction(block)
        }
    }

    fun submit(confirmed: FixedEventEditConfirmation? = null) {
        if (saving) return
        val startsAt: Long
        val endsAt: Long
        try {
            startsAt = parseEventEditTime(start, original.startsAt)
            endsAt = parseEventEditTime(end, original.endsAt)
            require(title.isNotBlank()) { "请输入事件标题。" }
            require(endsAt > startsAt) { "结束时间必须晚于开始时间。" }
        } catch (invalid: IllegalArgumentException) {
            error = invalid.message
            confirmation = null
            return
        }
        saving = true
        error = null
        scope.launch {
            try {
                when (val result = useCase.save(original, title, description, startsAt, endsAt, confirmed)) {
                    FixedEventEditResult.Saved -> onSaved()
                    is FixedEventEditResult.NeedsConfirmation -> confirmation = result.confirmation
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                confirmation = null
                error = if (failure is IllegalArgumentException || failure is IllegalStateException) {
                    failure.message ?: "保存失败，输入已保留，请重试。"
                } else "保存失败，输入已保留，请重试。"
            } finally {
                saving = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("编辑固定事件") },
        modifier = Modifier.widthIn(min = 480.dp, max = 600.dp).testTag("event-edit-dialog"),
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("时间使用上海时区（UTC+8）。保存会保留原所属工作区。")
                OutlinedTextField(title, { title = it; error = null; confirmation = null },
                    Modifier.fillMaxWidth().testTag("event-edit-title"), label = { Text("标题") },
                    enabled = !saving, singleLine = true)
                OutlinedTextField(description, { description = it; error = null; confirmation = null },
                    Modifier.fillMaxWidth().testTag("event-edit-description"), label = { Text("说明（选填）") },
                    enabled = !saving, minLines = 2, maxLines = 4)
                OutlinedTextField(start, { start = it; error = null; confirmation = null },
                    Modifier.fillMaxWidth().testTag("event-edit-start"), label = { Text("开始时间") },
                    placeholder = { Text("yyyy-MM-dd HH:mm") }, enabled = !saving, singleLine = true)
                OutlinedTextField(end, { end = it; error = null; confirmation = null },
                    Modifier.fillMaxWidth().testTag("event-edit-end"), label = { Text("结束时间") },
                    placeholder = { Text("yyyy-MM-dd HH:mm") }, enabled = !saving, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("event-edit-error")) }
            }
        },
        confirmButton = { Button(onClick = { submit() }, enabled = !saving) { Text(if (saving) "保存中…" else "保存") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } },
    )
    confirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = { if (!saving) confirmation = null },
            title = { Text("与本人日程重叠") },
            modifier = Modifier.testTag("event-conflict-dialog"),
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("以下安排与本次事件重叠。可返回修改，或明确保留重叠后保存。")
                    pending.events.forEach { conflict ->
                        Text("${conflict.title}\n${localMoment(conflict.startsAt)} — ${localMoment(conflict.endsAt)}")
                    }
                    pending.sessions.forEach { conflict ->
                        Text("我的工作时段\n${localMoment(conflict.startsAt)} — ${localMoment(conflict.endsAt)}")
                    }
                }
            },
            confirmButton = {
                Button(onClick = { submit(pending) }, enabled = !saving) { Text(if (saving) "正在复查…" else "仍然保存") }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }, enabled = !saving) { Text("返回修改") } },
        )
    }
}
