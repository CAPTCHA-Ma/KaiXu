package com.kaixu.desktop.presentation.ui.scheduling

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
import com.kaixu.domain.model.scheduling.EditScope
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.FixedEventDraft
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.repository.SchedulingTransaction
import com.kaixu.domain.usecase.scheduling.FixedEventConfirmation
import com.kaixu.domain.usecase.scheduling.FixedEventSaveResult
import com.kaixu.domain.usecase.scheduling.FixedEventSchedule
import com.kaixu.domain.usecase.scheduling.ScheduleConflictKind
import com.kaixu.domain.validation.scheduling.ShanghaiTime
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

/** 编辑对象：底层记录 + 被点击课次的实际起止；originalDate 非空表示重复序列中的某次。 */
internal data class EventEditorTarget(
    val event: FixedEvent,
    val occurrenceStart: Long,
    val occurrenceEnd: Long,
    val originalDate: String? = null,
)

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

internal fun defaultEventStart(): Long {
    val now = Instant.now().atZone(EventTimeZone)
    val rounded = now.plusHours(1).withMinute(0).withSecond(0).withNano(0)
    return rounded.toInstant().toEpochMilli()
}

private val weekdayLabels = listOf(1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六", 7 to "日")
private val reminderOptions = listOf<Int?>(null, 5, 15, 30, 60)

/** 新建/编辑/删除固定事件的对话框；创建与编辑共用同一套校验与冲突确认。 */
@Composable
internal fun FixedEventEditorDialog(
    sources: LocalDataSources,
    target: EventEditorTarget?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit = onSaved,
) {
    val schedule = remember(sources) {
        FixedEventSchedule(
            sources.fixedEvents, sources.workSessions, sources.workspaces, LOCAL_ACTOR_ID,
            object : SchedulingTransaction {
                override suspend fun <T> run(block: suspend () -> T): T = sources.writeTransaction(block)
            },
        )
    }
    val defaultStart = remember { defaultEventStart() }
    val baseStart = target?.occurrenceStart ?: defaultStart
    val baseEnd = target?.occurrenceEnd ?: (defaultStart + 60 * 60 * 1000)

    var title by remember { mutableStateOf(target?.event?.title.orEmpty()) }
    var description by remember { mutableStateOf(target?.event?.description.orEmpty()) }
    var start by remember { mutableStateOf(formatEventEditTime(baseStart)) }
    var end by remember { mutableStateOf(formatEventEditTime(baseEnd)) }
    var workspaceId by remember { mutableStateOf(target?.event?.workspaceId) }
    var repeatEnabled by remember { mutableStateOf(target?.event?.repeatWeekdays != null) }
    var weekdays by remember { mutableStateOf(target?.event?.repeatWeekdays ?: setOf(1)) }
    var repeatUntil by remember {
        mutableStateOf(
            target?.event?.repeatUntil
                ?: ShanghaiTime.plusDays(ShanghaiTime.localDateOf(baseStart), 28),
        )
    }
    var reminder by remember { mutableStateOf(target?.event?.reminderOffsetMinutes) }
    var scope by remember { mutableStateOf(EditScope.THIS_OCCURRENCE) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<FixedEventConfirmation?>(null) }
    val scopeOwner = rememberCoroutineScope()

    val workspaces by produceState<List<Workspace>>(emptyList(), sources) {
        value = try {
            sources.workspaces.listWorkspaces(WorkspaceStatus.ACTIVE)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun buildDraft(): FixedEventDraft {
        val startsAt = parseEventEditTime(start, baseStart)
        val endsAt = parseEventEditTime(end, baseEnd)
        require(title.isNotBlank()) { "请输入事件标题。" }
        require(endsAt > startsAt) { "结束时间必须晚于开始时间。" }
        return FixedEventDraft(
            title = title,
            startsAt = startsAt,
            endsAt = endsAt,
            description = description,
            workspaceId = workspaceId,
            repeatWeekdays = if (repeatEnabled) weekdays else null,
            repeatUntil = if (repeatEnabled) repeatUntil.trim() else null,
            reminderOffsetMinutes = reminder,
        )
    }

    fun submit(confirmed: FixedEventConfirmation? = null) {
        if (saving || deleting) return
        val draft = try {
            buildDraft()
        } catch (invalid: IllegalArgumentException) {
            error = invalid.message
            confirmation = null
            return
        }
        saving = true
        error = null
        scopeOwner.launch {
            try {
                val result = if (target == null) {
                    schedule.create(draft, confirmed)
                } else {
                    schedule.update(target.event, draft, target.originalDate, scope, confirmed)
                }
                when (result) {
                    is FixedEventSaveResult.Saved -> onSaved()
                    is FixedEventSaveResult.NeedsConfirmation -> confirmation = result.confirmation
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                confirmation = null
                error = failure.message ?: "保存失败，输入已保留，请重试。"
            } finally {
                saving = false
            }
        }
    }

    fun confirmDelete() {
        if (target == null || deleting) return
        deleting = true
        error = null
        scopeOwner.launch {
            try {
                schedule.delete(target.event, target.originalDate, scope)
                onDeleted()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "删除失败，请重试。"
            } finally {
                deleting = false
                showDeleteConfirm = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving && !deleting) onDismiss() },
        title = { Text(if (target == null) "新建固定事件" else "编辑固定事件") },
        modifier = Modifier.widthIn(min = 520.dp, max = 640.dp).testTag("event-editor-dialog"),
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("时间使用上海时区（UTC+8）。")
                OutlinedTextField(
                    title, { title = it; error = null; confirmation = null },
                    Modifier.fillMaxWidth().testTag("event-editor-title"),
                    label = { Text("标题") }, enabled = !saving, singleLine = true,
                )
                OutlinedTextField(
                    description, { description = it; error = null; confirmation = null },
                    Modifier.fillMaxWidth().testTag("event-editor-description"),
                    label = { Text("说明（选填）") }, enabled = !saving, minLines = 2, maxLines = 4,
                )
                OutlinedTextField(
                    start, { start = it; error = null; confirmation = null },
                    Modifier.fillMaxWidth().testTag("event-editor-start"),
                    label = { Text("开始时间") }, placeholder = { Text("yyyy-MM-dd HH:mm") },
                    enabled = !saving, singleLine = true,
                )
                OutlinedTextField(
                    end, { end = it; error = null; confirmation = null },
                    Modifier.fillMaxWidth().testTag("event-editor-end"),
                    label = { Text("结束时间") }, placeholder = { Text("yyyy-MM-dd HH:mm") },
                    enabled = !saving, singleLine = true,
                )
                WorkspacePicker(workspaces, workspaceId) { workspaceId = it; error = null; confirmation = null }
                ReminderPicker(reminder) { reminder = it; error = null; confirmation = null }

                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(
                        checked = repeatEnabled,
                        onCheckedChange = {
                            repeatEnabled = it
                            error = null
                            confirmation = null
                        },
                        enabled = !saving,
                        modifier = Modifier.testTag("event-editor-repeat"),
                    )
                    Text("每周重复")
                }
                if (repeatEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((value, label) in weekdayLabels) {
                            FilterChip(
                                selected = value in weekdays,
                                onClick = {
                                    weekdays = if (value in weekdays) weekdays - value else weekdays + value
                                    error = null
                                    confirmation = null
                                },
                                enabled = !saving,
                                label = { Text(label) },
                                modifier = Modifier.testTag("event-editor-weekday-$value"),
                            )
                        }
                    }
                    OutlinedTextField(
                        repeatUntil, { repeatUntil = it; error = null; confirmation = null },
                        Modifier.fillMaxWidth().testTag("event-editor-until"),
                        label = { Text("重复结束日期（含当天）") },
                        placeholder = { Text("yyyy-MM-dd") }, enabled = !saving, singleLine = true,
                    )
                }

                if (target?.originalDate != null) {
                    Text("修改范围", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = scope == EditScope.THIS_OCCURRENCE,
                            onClick = { scope = EditScope.THIS_OCCURRENCE; error = null; confirmation = null },
                            enabled = !saving,
                            label = { Text("仅本次") },
                            modifier = Modifier.testTag("event-editor-scope-this"),
                        )
                        FilterChip(
                            selected = scope == EditScope.THIS_AND_FUTURE,
                            onClick = { scope = EditScope.THIS_AND_FUTURE; error = null; confirmation = null },
                            enabled = !saving,
                            label = { Text("本次及后续") },
                            modifier = Modifier.testTag("event-editor-scope-future"),
                        )
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("event-editor-error"))
                }
            }
        },
        confirmButton = {
            Button(onClick = { submit() }, enabled = !saving && !deleting, modifier = Modifier.testTag("event-editor-save")) {
                Text(if (saving) "保存中…" else "保存")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (target != null) {
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        enabled = !saving && !deleting,
                        modifier = Modifier.testTag("event-editor-delete"),
                    ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss, enabled = !saving && !deleting) { Text("取消") }
            }
        },
    )

    confirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = { if (!saving) confirmation = null },
            title = { Text("与本人日程重叠") },
            modifier = Modifier.testTag("event-conflict-dialog"),
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("以下安排与本次事件重叠。可返回修改，或明确保留重叠后保存。")
                    pending.conflicts.forEach { conflict ->
                        val label = if (conflict.kind == ScheduleConflictKind.WORK_SESSION) "我的工作时段" else conflict.title
                        Text("$label\n${formatEventEditTime(conflict.startsAt)} — ${formatEventEditTime(conflict.endsAt)}")
                    }
                }
            },
            confirmButton = {
                Button(onClick = { submit(pending) }, enabled = !saving) {
                    Text(if (saving) "正在复查…" else "仍然保存")
                }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }, enabled = !saving) { Text("返回修改") } },
        )
    }

    if (showDeleteConfirm && target != null) {
        AlertDialog(
            onDismissRequest = { if (!deleting) showDeleteConfirm = false },
            title = { Text("删除固定事件？") },
            modifier = Modifier.testTag("event-delete-dialog"),
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("删除后不会出现在日程中。")
                    if (target.originalDate != null) {
                        Text("这是重复事件的一次课次，请选择删除范围。")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = scope == EditScope.THIS_OCCURRENCE,
                                onClick = { scope = EditScope.THIS_OCCURRENCE },
                                enabled = !deleting,
                                label = { Text("仅本次") },
                            )
                            FilterChip(
                                selected = scope == EditScope.THIS_AND_FUTURE,
                                onClick = { scope = EditScope.THIS_AND_FUTURE },
                                enabled = !deleting,
                                label = { Text("本次及后续") },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { confirmDelete() }, enabled = !deleting) {
                    Text(if (deleting) "删除中…" else "确认删除")
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }, enabled = !deleting) { Text("取消") } },
        )
    }
}

@Composable
private fun WorkspacePicker(
    workspaces: List<Workspace>,
    selectedId: String?,
    onSelected: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = workspaces.firstOrNull { it.id == selectedId }?.name
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().testTag("event-editor-workspace"),
        ) {
            Text(if (selectedId == null) "不关联工作区（独立事件）" else "工作区：${selectedName ?: "已不可用"}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("不关联工作区（独立事件）") },
                onClick = { onSelected(null); expanded = false },
            )
            for (workspace in workspaces) {
                DropdownMenuItem(
                    text = { Text("${workspace.name}（${if (workspace.kind.name == "COURSE") "课程" else "项目"}）") },
                    onClick = { onSelected(workspace.id); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun ReminderPicker(selected: Int?, onSelected: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().testTag("event-editor-reminder"),
        ) {
            Text(if (selected == null) "站内提醒：不设置" else "站内提醒：提前 $selected 分钟")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in reminderOptions) {
                DropdownMenuItem(
                    text = { Text(if (option == null) "不设置" else "提前 $option 分钟") },
                    onClick = { onSelected(option); expanded = false },
                )
            }
        }
    }
}
