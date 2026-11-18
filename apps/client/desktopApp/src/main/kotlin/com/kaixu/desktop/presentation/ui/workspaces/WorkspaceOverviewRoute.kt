package com.kaixu.desktop.presentation.ui.workspaces

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaixu.domain.model.tasks.TaskStatus
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.desktop.presentation.ui.scheduling.EventEditorTarget
import com.kaixu.desktop.presentation.ui.scheduling.FixedEventEditorDialog
import com.kaixu.domain.model.scheduling.ScheduleOccurrence
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.usecase.workspaces.WorkspaceDeadlineFilter
import com.kaixu.domain.usecase.workspaces.WorkspaceDeadlineWindow
import com.kaixu.presentation.viewmodel.workspaces.WorkspaceOverviewViewModel
import com.kaixu.desktop.presentation.ui.records.LocalRecordTarget
import com.kaixu.desktop.presentation.ui.records.RecordKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val Shanghai = ZoneId.of("Asia/Shanghai")

internal fun currentWorkspaceWindow(): WorkspaceDeadlineWindow {
    val now = Instant.now()
    val today = now.atZone(Shanghai).toLocalDate()
    val end = today.plusDays(7)
    return WorkspaceDeadlineWindow(today.toString(), now.toEpochMilli(), end.toString(), end.atStartOfDay(Shanghai).toInstant().toEpochMilli())
}

internal fun localMoment(millis: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(millis).atZone(Shanghai))

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkspaceOverviewRoute(
    workspace: Workspace,
    viewModel: WorkspaceOverviewViewModel,
    sources: LocalDataSources,
    onOpenRecord: (LocalRecordTarget) -> Unit,
) {
    val workspaceId = workspace.id
    val archived = workspace.status == WorkspaceStatus.ARCHIVED
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var eventTarget by remember(workspaceId) { mutableStateOf<EventEditorTarget?>(null) }
    var readOnlyOccurrence by remember(workspaceId) { mutableStateOf<ScheduleOccurrence?>(null) }
    var eventError by remember(workspaceId) { mutableStateOf<String?>(null) }
    var openingEvent by remember(workspaceId) { mutableStateOf(false) }
    LaunchedEffect(workspaceId) { viewModel.refresh(workspaceId) }
    var showAllTasks by androidx.compose.runtime.saveable.rememberSaveable(workspaceId) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.testTag("workspace-overview")) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("本地概览", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (archived) {
                Text(
                    "已归档",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("overview-archived"),
                )
                Spacer(Modifier.width(8.dp))
            }
            TextButton(onClick = { viewModel.refresh(workspaceId) }, enabled = !state.isLoading) { Text("刷新概览") }
        }
        if (archived) {
            Text("工作区已归档；已有任务、日程和工作时段继续显示，只读。", style = MaterialTheme.typography.bodySmall)
        }
        if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.loadError?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { viewModel.refresh(workspaceId) }) { Text("重试概览") }
        }
        val data = state.overview
        if (data != null && data.workspaceId == workspaceId) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for ((label, value) in listOf("未完成" to data.incompleteTaskCount, "已完成" to data.completedTaskCount, "近期截止" to data.upcomingTasks.size)) {
                    Surface(Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(label)
                            Text(value.toString(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            Surface(shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row {
                        Text("工作区任务", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showAllTasks = !showAllTasks }) { Text(if (showAllTasks) "收起任务" else "全部任务") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((status, label) in listOf(null to "全部状态", TaskStatus.TODO to "待办", TaskStatus.IN_PROGRESS to "进行中", TaskStatus.COMPLETED to "已完成")) {
                            FilterChip(selected = state.statusFilter == status,
                                modifier = Modifier.testTag("overview-status-${status?.name ?: "ALL"}"),
                                onClick = { viewModel.onStatusFilterChanged(status) }, label = { Text(label) })
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((filter, label) in listOf(
                            WorkspaceDeadlineFilter.ALL to "全部截止",
                            WorkspaceDeadlineFilter.OVERDUE to "已过截止",
                            WorkspaceDeadlineFilter.NEXT_SEVEN_DAYS to "近七天",
                            WorkspaceDeadlineFilter.NO_DEADLINE to "无截止",
                        )) FilterChip(selected = state.deadlineFilter == filter,
                            modifier = Modifier.testTag("overview-deadline-${filter.name}"),
                            onClick = { viewModel.onDeadlineFilterChanged(filter) }, label = { Text(label) })
                    }
                    val ordered = data.tasks.sortedBy { it.status == TaskStatus.COMPLETED }
                    val shown = if (showAllTasks) ordered else ordered.take(4)
                    if (shown.isEmpty()) Text("没有符合条件的任务")
                    shown.forEach { task ->
                        OutlinedButton(
                            onClick = { onOpenRecord(LocalRecordTarget(RecordKind.TASK, task.id)) },
                            modifier = Modifier.fillMaxWidth().testTag("overview-task-${task.id}"),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(task.title)
                                Text("截止要求：" + (task.deadlineDate ?: task.deadlineAt?.let(::localMoment) ?: "无截止"),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Text(when (task.status) {
                                TaskStatus.TODO -> "待办"
                                TaskStatus.IN_PROGRESS -> "进行中"
                                TaskStatus.COMPLETED -> "已完成"
                            })
                        }
                    }
                    if (!showAllTasks && ordered.size > 4) Text("显示 ${shown.size} / ${ordered.size} 项")
                }
            }
            Surface(shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("执行时间：日程与我的工作时段", style = MaterialTheme.typography.titleMedium)
                    Text("截止要求显示在任务列表中，不进入执行时间。", style = MaterialTheme.typography.bodySmall)
                    if (data.recurringOccurrences.isNotEmpty()) Text("重复事件显示近七天课次", style = MaterialTheme.typography.bodySmall)
                    eventError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (data.fixedEvents.isEmpty() && data.workSessions.isEmpty()) Text("暂无相关日程")
                    data.fixedEvents.forEach { event ->
                        val occurrence = data.recurringOccurrences.firstOrNull { it.eventId == event.id && it.startsAt == event.startsAt }
                        OutlinedButton(
                            onClick = {
                                if (occurrence == null) {
                                    onOpenRecord(LocalRecordTarget(RecordKind.EVENT, event.id))
                                } else if (!openingEvent) {
                                    openingEvent = true
                                    eventError = null
                                    scope.launch {
                                        try {
                                            val row = sources.fixedEvents.findEventById(requireNotNull(occurrence.seriesId))
                                            if (row == null) eventError = "事件已不可用，请刷新概览。"
                                            else {
                                                val currentWorkspace = sources.workspaces.findWorkspaceById(workspaceId)
                                                val seriesWorkspace = row.workspaceId?.let { sources.workspaces.findWorkspaceById(it) }
                                                val canEdit = row.createdBy == LOCAL_ACTOR_ID &&
                                                    currentWorkspace?.ownerUserId == LOCAL_ACTOR_ID &&
                                                    currentWorkspace.status == WorkspaceStatus.ACTIVE &&
                                                    (row.workspaceId == null || (seriesWorkspace?.ownerUserId == LOCAL_ACTOR_ID &&
                                                        seriesWorkspace.status == WorkspaceStatus.ACTIVE))
                                                if (!canEdit) readOnlyOccurrence = occurrence
                                                else eventTarget = EventEditorTarget(
                                                    row.copy(title = occurrence.title, description = occurrence.description,
                                                        workspaceId = occurrence.workspaceId,
                                                        reminderOffsetMinutes = occurrence.reminderOffsetMinutes),
                                                    occurrence.startsAt, occurrence.endsAt, occurrence.originalDate,
                                                )
                                            }
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            eventError = "读取事件失败，请重试。"
                                        } finally { openingEvent = false }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag(
                                if (occurrence == null) "overview-event-${event.id}" else "overview-event-${occurrence.key}"
                            ),
                        ) { Text("${event.title}   ${localMoment(event.startsAt)}   固定事件" + (if (occurrence == null) "" else " · 重复") + if (archived) " · 已归档" else "") }
                    }
                    data.workSessions.forEach { session ->
                        OutlinedButton(
                            onClick = { onOpenRecord(LocalRecordTarget(RecordKind.SESSION, session.id)) },
                            modifier = Modifier.fillMaxWidth().testTag("overview-session-${session.id}"),
                        ) { Text("我的工作时段   ${localMoment(session.startsAt)}" + if (archived) " · 已归档" else "") }
                    }
                }
            }
        }
    }
    readOnlyOccurrence?.let { occurrence ->
        AlertDialog(
            onDismissRequest = { readOnlyOccurrence = null },
            title = { Text(occurrence.title) },
            text = { Text("${localMoment(occurrence.startsAt)} — ${localMoment(occurrence.endsAt)}\n该课次只读。", modifier = Modifier.testTag("overview-occurrence-read-only")) },
            confirmButton = { TextButton(onClick = { readOnlyOccurrence = null }) { Text("关闭") } },
        )
    }
    eventTarget?.let { target ->
        FixedEventEditorDialog(
            sources = sources, target = target,
            onDismiss = { eventTarget = null },
            onSaved = { eventTarget = null; viewModel.refresh(workspaceId) },
            onDeleted = { eventTarget = null; viewModel.refresh(workspaceId) },
        )
    }
}
