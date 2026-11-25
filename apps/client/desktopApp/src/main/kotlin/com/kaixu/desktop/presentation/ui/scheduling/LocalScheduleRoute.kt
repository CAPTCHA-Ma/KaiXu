package com.kaixu.desktop.presentation.ui.scheduling

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.desktop.presentation.ui.records.LocalRecordTarget
import com.kaixu.desktop.presentation.ui.records.RecordKind
import com.kaixu.desktop.presentation.ui.workspaces.localMoment
import com.kaixu.domain.model.scheduling.ScheduleDeadline
import com.kaixu.domain.model.scheduling.ScheduleEntry
import com.kaixu.domain.model.scheduling.ScheduleEntryType
import com.kaixu.domain.model.scheduling.ScheduleView
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.usecase.scheduling.ScheduleWorkspaceFilter
import com.kaixu.domain.usecase.scheduling.ViewScheduleUseCase
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException

private val ScheduleZone = ZoneId.of("Asia/Shanghai")
private val DayLabelFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd E", Locale.CHINA)

internal enum class ScheduleMode { DAY, WEEK }

/** 桌面日程页：日/周视图、工作区筛选与固定事件的新建入口。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalScheduleRoute(
    sources: LocalDataSources,
    onOpenRecord: (LocalRecordTarget) -> Unit,
) {
    val schedule = remember(sources) {
        ViewScheduleUseCase(
            sources.fixedEvents, sources.workSessions, sources.tasks, sources.workspaces, LOCAL_ACTOR_ID, sources.transaction,
        )
    }
    var mode by remember { mutableStateOf(ScheduleMode.DAY) }
    var selectedDate by remember { mutableStateOf(LocalDate.now(ScheduleZone)) }
    var filter by remember { mutableStateOf<ScheduleWorkspaceFilter>(ScheduleWorkspaceFilter.All) }
    var reload by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }

    val workspaces by produceState<List<Workspace>>(emptyList(), sources, reload) {
        value = try {
            sources.workspaces.listWorkspaces(null)
                .filter { it.ownerUserId == LOCAL_ACTOR_ID }
                .sortedWith(compareBy({ it.status == WorkspaceStatus.ARCHIVED }, { it.name }))
        } catch (_: Exception) {
            emptyList()
        }
    }
    val window = remember(mode, selectedDate) { windowFor(mode, selectedDate) }
    val state by produceState<Result<ScheduleView>?>(null, window, filter, reload) {
        value = null
        value = try {
            Result.success(schedule(window.first, window.second, filter))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    val openEntry: (ScheduleEntry) -> Unit = { entry ->
        val kind = if (entry.type == ScheduleEntryType.WORK_SESSION) RecordKind.SESSION else RecordKind.EVENT
        onOpenRecord(LocalRecordTarget(kind, entry.recordId))
    }
    val openDeadline: (ScheduleDeadline) -> Unit = { deadline ->
        onOpenRecord(LocalRecordTarget(RecordKind.TASK, deadline.taskId))
    }

    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("日程", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Button(onClick = { creating = true }, modifier = Modifier.testTag("schedule-create")) {
                Text("新建固定事件")
            }
        }
        Text("从本地库读取，无需登录或联网。日视图、周视图与工作区筛选共用当前日期范围。", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == ScheduleMode.DAY,
                onClick = { mode = ScheduleMode.DAY },
                label = { Text("日视图") },
                modifier = Modifier.testTag("schedule-mode-day"),
            )
            FilterChip(
                selected = mode == ScheduleMode.WEEK,
                onClick = { mode = ScheduleMode.WEEK },
                label = { Text("周视图") },
                modifier = Modifier.testTag("schedule-mode-week"),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { selectedDate = shiftDate(mode, selectedDate, -1) }) {
                Text(if (mode == ScheduleMode.WEEK) "上一周" else "前一天")
            }
            TextButton(onClick = { selectedDate = LocalDate.now(ScheduleZone) }) { Text("今天") }
            TextButton(onClick = { selectedDate = shiftDate(mode, selectedDate, 1) }) {
                Text(if (mode == ScheduleMode.WEEK) "下一周" else "后一天")
            }
            Text(
                text = if (mode == ScheduleMode.WEEK) weekLabel(selectedDate) else DayLabelFormat.format(selectedDate),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        WorkspaceFilterRow(workspaces, filter) { filter = it }
        Text(
            "类型标识：固定事件 / 共享事件 / 我的工作时段（仅我）。截止要求单独列出，不进入执行时间轴。",
            style = MaterialTheme.typography.bodySmall,
        )

        val snapshot = state
        when {
            snapshot == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            snapshot.isFailure -> {
                Text("读取日程失败。", color = MaterialTheme.colorScheme.error)
                Button(onClick = { reload++ }) { Text("重试") }
            }
            else -> {
                val view = snapshot.getOrThrow()
                if (mode == ScheduleMode.DAY) {
                    DaySchedule(selectedDate, view, openEntry, openDeadline)
                } else {
                    WeekSchedule(selectedDate.with(DayOfWeek.MONDAY), view, openEntry, openDeadline)
                }
            }
        }
    }

    if (creating) {
        FixedEventEditorDialog(
            sources = sources,
            target = null,
            onDismiss = { creating = false },
            onSaved = { creating = false; reload++ },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkspaceFilterRow(
    workspaces: List<Workspace>,
    selected: ScheduleWorkspaceFilter,
    onSelected: (ScheduleWorkspaceFilter) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(
            selected = selected == ScheduleWorkspaceFilter.All,
            onClick = { onSelected(ScheduleWorkspaceFilter.All) },
            label = { Text("全部工作区") },
            modifier = Modifier.testTag("schedule-filter-all"),
        )
        FilterChip(
            selected = selected == ScheduleWorkspaceFilter.Standalone,
            onClick = { onSelected(ScheduleWorkspaceFilter.Standalone) },
            label = { Text("独立安排") },
            modifier = Modifier.testTag("schedule-filter-standalone"),
        )
        for (workspace in workspaces) {
            val value = ScheduleWorkspaceFilter.Workspace(workspace.id)
            FilterChip(
                selected = selected == value,
                onClick = { onSelected(value) },
                label = {
                    Text(workspace.name + if (workspace.status == WorkspaceStatus.ARCHIVED) "（已归档）" else "")
                },
                modifier = Modifier.testTag("schedule-filter-${workspace.id}"),
            )
        }
    }
}

@Composable
private fun DaySchedule(
    date: LocalDate,
    view: ScheduleView,
    onOpenEntry: (ScheduleEntry) -> Unit,
    onOpenDeadline: (ScheduleDeadline) -> Unit,
) {
    val entries = view.entries.filter { localDateOf(it.startsAt) == date }
    val deadlines = view.deadlines.filter { deadlineDate(it) == date }
    if (entries.isEmpty() && deadlines.isEmpty()) {
        Text("这一天没有安排。", modifier = Modifier.testTag("schedule-empty"))
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (entries.isNotEmpty()) {
            item(key = "section-entries") { SectionLabel("执行时间") }
            items(entries, key = { "entry-${it.key}" }) { entry -> EntryRow(entry, onOpenEntry) }
        }
        if (deadlines.isNotEmpty()) {
            item(key = "section-deadlines") { SectionLabel("截止要求（不进入执行时间）") }
            items(deadlines, key = { "deadline-${it.key}" }) { deadline -> DeadlineRow(deadline, onOpenDeadline) }
        }
    }
}

@Composable
private fun WeekSchedule(
    startDate: LocalDate,
    view: ScheduleView,
    onOpenEntry: (ScheduleEntry) -> Unit,
    onOpenDeadline: (ScheduleDeadline) -> Unit,
) {
    val entriesByDate = view.entries.groupBy { localDateOf(it.startsAt) }
    val deadlinesByDate = view.deadlines.groupBy { deadlineDate(it) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for (offset in 0 until 7) {
            val date = startDate.plusDays(offset.toLong())
            val entries = entriesByDate[date].orEmpty()
            val deadlines = deadlinesByDate[date].orEmpty()
            item(key = "day-$date") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag("schedule-day-$date"),
                ) {
                    Text(DayLabelFormat.format(date), style = MaterialTheme.typography.titleMedium)
                    if (entries.isEmpty() && deadlines.isEmpty()) {
                        Text("无安排", style = MaterialTheme.typography.bodySmall)
                    }
                    entries.forEach { entry -> EntryRow(entry, onOpenEntry) }
                    if (deadlines.isNotEmpty()) {
                        SectionLabel("截止要求")
                        deadlines.forEach { deadline -> DeadlineRow(deadline, onOpenDeadline) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun EntryRow(entry: ScheduleEntry, onOpen: (ScheduleEntry) -> Unit) {
    OutlinedButton(
        onClick = { onOpen(entry) },
        modifier = Modifier.fillMaxWidth().testTag("schedule-entry-${entry.key}"),
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.title)
            Text(
                "${clockOf(entry.startsAt)} — ${clockOf(entry.endsAt)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(entryMarkers(entry), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun DeadlineRow(deadline: ScheduleDeadline, onOpen: (ScheduleDeadline) -> Unit) {
    OutlinedButton(
        onClick = { onOpen(deadline) },
        modifier = Modifier.fillMaxWidth().testTag("schedule-deadline-${deadline.taskId}"),
    ) {
        Column(Modifier.weight(1f)) {
            Text("截止要求 · ${deadline.title}")
            Text(deadlineLabel(deadline), style = MaterialTheme.typography.bodySmall)
            val markers = buildList {
                deadline.workspaceName?.let(::add)
                if (deadline.workspaceArchived) add("已归档")
            }
            if (markers.isNotEmpty()) Text(markers.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun entryMarkers(entry: ScheduleEntry): String = buildList {
    add(
        when (entry.type) {
            ScheduleEntryType.FIXED_EVENT -> "固定事件"
            ScheduleEntryType.SHARED_EVENT -> "共享事件"
            ScheduleEntryType.WORK_SESSION -> "我的工作时段（仅我）"
        },
    )
    if (entry.isRecurring) add("重复")
    entry.workspaceName?.let(::add)
    if (entry.workspaceArchived) add("已归档")
}.joinToString(" · ")

private fun deadlineLabel(deadline: ScheduleDeadline): String {
    val instant = deadline.at
    return when {
        deadline.date != null -> "${deadline.date}（仅日期）"
        instant != null -> "${localMoment(instant)}（具体时间）"
        else -> "无截止"
    }
}

private fun deadlineDate(deadline: ScheduleDeadline): LocalDate? =
    deadline.date?.let { LocalDate.parse(it) } ?: deadline.at?.let(::localDateOf)

private fun windowFor(mode: ScheduleMode, date: LocalDate): Pair<Long, Long> {
    val start = if (mode == ScheduleMode.WEEK) date.with(DayOfWeek.MONDAY) else date
    val end = if (mode == ScheduleMode.WEEK) start.plusDays(7) else start.plusDays(1)
    return start.atStartOfDay(ScheduleZone).toInstant().toEpochMilli() to
        end.atStartOfDay(ScheduleZone).toInstant().toEpochMilli()
}

private fun shiftDate(mode: ScheduleMode, date: LocalDate, direction: Long): LocalDate =
    if (mode == ScheduleMode.WEEK) date.plusWeeks(direction) else date.plusDays(direction)

private fun weekLabel(date: LocalDate): String {
    val monday = date.with(DayOfWeek.MONDAY)
    val sunday = monday.plusDays(6)
    return "$monday ~ $sunday"
}

private fun localDateOf(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ScheduleZone).toLocalDate()

private fun clockOf(millis: Long): String =
    formatEventEditTime(millis).substring(11)
