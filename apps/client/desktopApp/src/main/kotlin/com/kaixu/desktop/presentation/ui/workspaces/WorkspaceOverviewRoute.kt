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
import com.kaixu.domain.usecase.workspaces.WorkspaceDeadlineFilter
import com.kaixu.domain.usecase.workspaces.WorkspaceDeadlineWindow
import com.kaixu.presentation.viewmodel.workspaces.WorkspaceOverviewViewModel
import com.kaixu.desktop.presentation.ui.records.LocalRecordTarget
import com.kaixu.desktop.presentation.ui.records.RecordKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    workspaceId: String,
    viewModel: WorkspaceOverviewViewModel,
    onOpenRecord: (LocalRecordTarget) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(workspaceId) { viewModel.refresh(workspaceId) }
    var showAllTasks by androidx.compose.runtime.saveable.rememberSaveable(workspaceId) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.testTag("workspace-overview")) {
        Row {
            Text("本地概览", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { viewModel.refresh(workspaceId) }, enabled = !state.isLoading) { Text("刷新概览") }
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
                                Text(task.deadlineDate ?: task.deadlineAt?.let(::localMoment) ?: "无截止",
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
                    Text("日程与我的工作时段", style = MaterialTheme.typography.titleMedium)
                    if (data.fixedEvents.isEmpty() && data.workSessions.isEmpty()) Text("暂无相关日程")
                    data.fixedEvents.forEach { event ->
                        OutlinedButton(
                            onClick = { onOpenRecord(LocalRecordTarget(RecordKind.EVENT, event.id)) },
                            modifier = Modifier.fillMaxWidth().testTag("overview-event-${event.id}"),
                        ) { Text("${event.title}   ${localMoment(event.startsAt)}") }
                    }
                    data.workSessions.forEach { session ->
                        OutlinedButton(
                            onClick = { onOpenRecord(LocalRecordTarget(RecordKind.SESSION, session.id)) },
                            modifier = Modifier.fillMaxWidth().testTag("overview-session-${session.id}"),
                        ) { Text("我的工作时段   ${localMoment(session.startsAt)}") }
                    }
                }
            }
        }
    }
}
