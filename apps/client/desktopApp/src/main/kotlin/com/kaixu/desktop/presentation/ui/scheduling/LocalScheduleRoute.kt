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
import com.kaixu.core.platform.LocalZone
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.desktop.presentation.ui.records.LocalRecordTarget
import com.kaixu.desktop.presentation.ui.records.RecordKind
import com.kaixu.desktop.presentation.ui.workspaces.localMoment
import com.kaixu.domain.model.scheduling.CalendarBlockKind
import com.kaixu.domain.model.scheduling.CalendarDayColumn
import com.kaixu.domain.model.scheduling.CalendarDaySlice
import com.kaixu.domain.model.scheduling.CalendarDeadlineItem
import com.kaixu.domain.model.scheduling.CalendarQuery
import com.kaixu.domain.model.scheduling.CalendarViewMode
import com.kaixu.domain.model.scheduling.CalendarWindowSnapshot
import com.kaixu.domain.model.scheduling.CalendarWorkspaceFilter
import com.kaixu.domain.model.tasks.TaskDeadline
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.usecase.scheduling.FixedEventUseCases
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException

private val ScheduleZone = ZoneId.of("Asia/Shanghai")
private val ScheduleLocalZone = LocalZone.of("Asia/Shanghai")
private val DayLabelFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd E", Locale.CHINA)

/** 桌面日程页：日/周视图、工作区筛选与固定事件的新建入口。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalScheduleRoute(
    sources: LocalDataSources,
    onOpenRecord: (LocalRecordTarget) -> Unit,
    fixedEventUseCases: FixedEventUseCases? = null,
) {
    val activeUseCases = fixedEventUseCases ?: remember(sources) { localFixedEventUseCases(sources) }
    val revision by activeUseCases.revision.collectAsState()
    var mode by remember { mutableStateOf(CalendarViewMode.DAY) }
    var selectedDate by remember { mutableStateOf(LocalDate.now(ScheduleZone)) }
    var filter by remember { mutableStateOf<CalendarWorkspaceFilter>(CalendarWorkspaceFilter.All) }
    var reload by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }

    val workspaces by produceState<List<Workspace>>(emptyList(), sources, reload, revision) {
        value = try {
            sources.workspaces.listWorkspaces(null)
                .filter { it.ownerUserId == LOCAL_ACTOR_ID }
                .sortedWith(compareBy({ it.status == WorkspaceStatus.ARCHIVED }, { it.name }))
        } catch (_: Exception) {
            emptyList()
        }
    }
    val state by produceState<Result<CalendarWindowSnapshot>?>(
        null, mode, selectedDate, filter, reload, revision, activeUseCases,
    ) {
        value = null
        value = try {
            Result.success(
                activeUseCases.queryCalendar(
                    query = CalendarQuery(
                        mode = mode,
                        anchorDate = selectedDate.toString(),
                        workspaceFilter = filter,
                    ),
                    zone = ScheduleLocalZone,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    val openSlice: (CalendarDaySlice) -> Unit = { slice ->
        val kind = if (slice.block.kind == CalendarBlockKind.WORK_SESSION) RecordKind.SESSION else RecordKind.EVENT
        onOpenRecord(LocalRecordTarget(kind, slice.block.recordId))
    }
    val openDeadline: (CalendarDeadlineItem) -> Unit = { deadline ->
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
                selected = mode == CalendarViewMode.DAY,
                onClick = { mode = CalendarViewMode.DAY },
                label = { Text("日视图") },
                modifier = Modifier.testTag("schedule-mode-day"),
            )
            FilterChip(
                selected = mode == CalendarViewMode.WEEK,
                onClick = { mode = CalendarViewMode.WEEK },
                label = { Text("周视图") },
                modifier = Modifier.testTag("schedule-mode-week"),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { selectedDate = shiftDate(mode, selectedDate, -1) }) {
                Text(if (mode == CalendarViewMode.WEEK) "上一周" else "前一天")
            }
            TextButton(onClick = { selectedDate = LocalDate.now(ScheduleZone) }) { Text("今天") }
            TextButton(onClick = { selectedDate = shiftDate(mode, selectedDate, 1) }) {
                Text(if (mode == CalendarViewMode.WEEK) "下一周" else "后一天")
            }
            Text(
                text = if (mode == CalendarViewMode.WEEK) weekLabel(selectedDate) else DayLabelFormat.format(selectedDate),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        WorkspaceFilterRow(workspaces, filter) { filter = it }
        Text(
            "类型标识：固定事件 / 共享事件 / 我的工作时段（仅我）。截止要求单独列出，不进入执行时间轴。",
            style = MaterialTheme.typography.bodySmall,
        )

        val snapshot = state
        val expectedAnchorDate = selectedDate.toString()
        when {
            snapshot == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            snapshot.isFailure -> {
                Text("读取日程失败。", color = MaterialTheme.colorScheme.error)
                Button(onClick = { reload++ }) { Text("重试") }
            }
            else -> {
                val view = snapshot.getOrThrow()
                val matchesQuery = view.spec.mode == mode &&
                    view.spec.anchorDate == expectedAnchorDate &&
                    view.workspaceFilter == filter
                when {
                    !matchesQuery -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    view.spec.mode == CalendarViewMode.DAY -> DaySchedule(view.days.single(), openSlice, openDeadline)
                    else -> WeekSchedule(view, openSlice, openDeadline)
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
            fixedEventUseCases = activeUseCases,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkspaceFilterRow(
    workspaces: List<Workspace>,
    selected: CalendarWorkspaceFilter,
    onSelected: (CalendarWorkspaceFilter) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(
            selected = selected == CalendarWorkspaceFilter.All,
            onClick = { onSelected(CalendarWorkspaceFilter.All) },
            label = { Text("全部工作区") },
            modifier = Modifier.testTag("schedule-filter-all"),
        )
        FilterChip(
            selected = selected == CalendarWorkspaceFilter.Standalone,
            onClick = { onSelected(CalendarWorkspaceFilter.Standalone) },
            label = { Text("独立安排") },
            modifier = Modifier.testTag("schedule-filter-standalone"),
        )
        for (workspace in workspaces) {
            val value = CalendarWorkspaceFilter.Workspace(workspace.id)
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
    day: CalendarDayColumn,
    onOpenSlice: (CalendarDaySlice) -> Unit,
    onOpenDeadline: (CalendarDeadlineItem) -> Unit,
) {
    if (day.slices.isEmpty() && day.deadlines.isEmpty()) {
        Text("这一天没有安排。", modifier = Modifier.testTag("schedule-empty"))
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (day.slices.isNotEmpty()) {
            item(key = "section-entries") { SectionLabel("执行时间") }
            items(day.slices, key = { "entry-${it.sliceKey}" }) { slice -> SliceRow(slice, onOpenSlice) }
        }
        if (day.deadlines.isNotEmpty()) {
            item(key = "section-deadlines") { SectionLabel("截止要求（不进入执行时间）") }
            items(day.deadlines, key = { "deadline-${it.key}" }) { deadline -> DeadlineRow(deadline, onOpenDeadline) }
        }
    }
}

@Composable
private fun WeekSchedule(
    snapshot: CalendarWindowSnapshot,
    onOpenSlice: (CalendarDaySlice) -> Unit,
    onOpenDeadline: (CalendarDeadlineItem) -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for (day in snapshot.days) {
            val date = LocalDate.parse(day.date)
            item(key = "day-${day.date}") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag("schedule-day-${day.date}"),
                ) {
                    Text(DayLabelFormat.format(date), style = MaterialTheme.typography.titleMedium)
                    if (day.slices.isEmpty() && day.deadlines.isEmpty()) {
                        Text("无安排", style = MaterialTheme.typography.bodySmall)
                    }
                    day.slices.forEach { slice -> SliceRow(slice, onOpenSlice) }
                    if (day.deadlines.isNotEmpty()) {
                        SectionLabel("截止要求")
                        day.deadlines.forEach { deadline -> DeadlineRow(deadline, onOpenDeadline) }
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
private fun SliceRow(slice: CalendarDaySlice, onOpen: (CalendarDaySlice) -> Unit) {
    val block = slice.block
    val crossDaySuffix = buildString {
        if (slice.continuesFromPreviousDay) append("（接前日）")
        if (slice.continuesToNextDay) append("（续次日）")
    }
    OutlinedButton(
        onClick = { onOpen(slice) },
        modifier = Modifier.fillMaxWidth().testTag("schedule-entry-${block.key}"),
    ) {
        Column(Modifier.weight(1f)) {
            Text(block.title)
            Text(
                "${clockOf(slice.sliceStartMillis)} — ${clockOf(slice.sliceEndMillis)}$crossDaySuffix",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(sliceMarkers(slice), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun DeadlineRow(deadline: CalendarDeadlineItem, onOpen: (CalendarDeadlineItem) -> Unit) {
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

private fun sliceMarkers(slice: CalendarDaySlice): String = buildList {
    val block = slice.block
    add(
        when (block.kind) {
            CalendarBlockKind.FIXED_EVENT -> "固定事件"
            CalendarBlockKind.SHARED_EVENT -> "共享事件"
            CalendarBlockKind.WORK_SESSION -> "我的工作时段（仅我）"
        },
    )
    if (block.isRecurring) add("重复")
    block.workspaceName?.let(::add)
    if (block.workspaceArchived) add("已归档")
}.joinToString(" · ")

private fun deadlineLabel(deadline: CalendarDeadlineItem): String = when (val value = deadline.deadline) {
    is TaskDeadline.DateOnly -> "${value.date}（仅日期）"
    is TaskDeadline.ExactTime -> "${localMoment(value.epochMillis)}（具体时间）"
    TaskDeadline.None -> "无截止"
}

private fun shiftDate(mode: CalendarViewMode, date: LocalDate, direction: Long): LocalDate =
    if (mode == CalendarViewMode.WEEK) date.plusWeeks(direction) else date.plusDays(direction)

private fun weekLabel(date: LocalDate): String {
    val monday = date.with(java.time.DayOfWeek.MONDAY)
    val sunday = monday.plusDays(6)
    return "$monday ~ $sunday"
}

private fun clockOf(millis: Long): String =
    formatEventEditTime(millis).substring(11)
