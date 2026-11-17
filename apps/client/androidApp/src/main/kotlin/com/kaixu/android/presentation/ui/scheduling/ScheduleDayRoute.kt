package com.kaixu.android.presentation.ui.scheduling

import androidx.compose.foundation.clickable
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
import com.kaixu.domain.model.scheduling.ScheduleDeadline
import com.kaixu.domain.model.scheduling.ScheduleEntry
import com.kaixu.domain.model.scheduling.ScheduleEntryType
import com.kaixu.domain.model.scheduling.ScheduleView
import com.kaixu.domain.repository.SchedulingTransaction
import com.kaixu.domain.usecase.scheduling.ViewScheduleUseCase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException

private val ScheduleZone = ZoneId.of("Asia/Shanghai")
private val DayLabel = DateTimeFormatter.ofPattern("yyyy-MM-dd E", Locale.CHINA)

private sealed interface ScheduleDetail {
    data class EntryDetail(val entry: ScheduleEntry) : ScheduleDetail
    data class DeadlineDetail(val deadline: ScheduleDeadline) : ScheduleDetail
}

/** Android 只读日程：按日期聚合本人固定事件、工作时段和可访问共享事件，无写入入口。 */
@Composable
fun ScheduleDayRoute(sources: LocalDataSources, onBack: () -> Unit) {
    val schedule = remember(sources) {
        ViewScheduleUseCase(
            sources.fixedEvents, sources.workSessions, sources.tasks, sources.workspaces, LOCAL_ACTOR_ID,
            object : SchedulingTransaction {
                override suspend fun <T> run(block: suspend () -> T): T = sources.writeTransaction(block)
            },
        )
    }
    var selectedDate by remember { mutableStateOf(LocalDate.now(ScheduleZone)) }
    var retry by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<ScheduleDetail?>(null) }
    val result by produceState<Result<ScheduleView>?>(null, selectedDate, retry, sources) {
        value = null
        val start = selectedDate.atStartOfDay(ScheduleZone).toInstant().toEpochMilli()
        val end = selectedDate.plusDays(1).atStartOfDay(ScheduleZone).toInstant().toEpochMilli()
        value = try {
            Result.success(schedule(start, end))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("日程", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("返回任务") }
        }
        Text("只读查看，事件在桌面端维护", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { selectedDate = selectedDate.minusDays(1) }) { Text("前一天") }
            TextButton(onClick = { selectedDate = LocalDate.now(ScheduleZone) }) { Text("今天") }
            TextButton(onClick = { selectedDate = selectedDate.plusDays(1) }) { Text("后一天") }
        }
        Text(DayLabel.format(selectedDate), style = MaterialTheme.typography.titleMedium)

        val snapshot = result
        when {
            snapshot == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            snapshot.isFailure -> {
                Text("读取日程失败。", color = MaterialTheme.colorScheme.error)
                Button(onClick = { retry++ }) { Text("重试") }
            }
            else -> {
                val view = snapshot.getOrThrow()
                if (view.entries.isEmpty() && view.deadlines.isEmpty()) {
                    Text("这一天没有安排。", modifier = Modifier.testTag("android-schedule-empty"))
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (view.entries.isNotEmpty()) {
                            item(key = "entries-label") { Text("执行时间", style = MaterialTheme.typography.labelLarge) }
                            items(view.entries, key = { "entry-${it.key}" }) { entry ->
                                ListItem(
                                    headlineContent = { Text(entry.title) },
                                    supportingContent = {
                                        Column {
                                            Text("${clockOf(entry.startsAt)} — ${clockOf(entry.endsAt)}")
                                            Text(entryMarkers(entry))
                                        }
                                    },
                                    modifier = Modifier
                                        .clickable { detail = ScheduleDetail.EntryDetail(entry) }
                                        .testTag("android-schedule-entry-${entry.key}"),
                                )
                            }
                        }
                        if (view.deadlines.isNotEmpty()) {
                            item(key = "deadlines-label") { Text("截止要求", style = MaterialTheme.typography.labelLarge) }
                            items(view.deadlines, key = { "deadline-${it.key}" }) { deadline ->
                                ListItem(
                                    headlineContent = { Text(deadline.title) },
                                    supportingContent = { Text(deadlineLabel(deadline)) },
                                    modifier = Modifier
                                        .clickable { detail = ScheduleDetail.DeadlineDetail(deadline) }
                                        .testTag("android-schedule-deadline-${deadline.taskId}"),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    detail?.let { current ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = {
                Text(
                    when (current) {
                        is ScheduleDetail.EntryDetail -> current.entry.title
                        is ScheduleDetail.DeadlineDetail -> "截止要求 · ${current.deadline.title}"
                    },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (current) {
                        is ScheduleDetail.EntryDetail -> {
                            Text("类型：${entryTypeLabel(current.entry.type)}")
                            Text("执行时间：${clockOf(current.entry.startsAt)} — ${clockOf(current.entry.endsAt)}")
                            current.entry.workspaceName?.let { Text("所属工作区：$it") }
                            if (current.entry.workspaceArchived) Text("工作区已归档，安排只读")
                            if (current.entry.isRecurring) Text("重复课次")
                        }
                        is ScheduleDetail.DeadlineDetail -> {
                            Text("截止要求：${deadlineLabel(current.deadline)}")
                            current.deadline.workspaceName?.let { Text("所属工作区：$it") }
                            if (current.deadline.workspaceArchived) Text("工作区已归档")
                            Text("截止要求不占用执行时间。")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } },
        )
    }
}

private fun entryTypeLabel(type: ScheduleEntryType): String = when (type) {
    ScheduleEntryType.FIXED_EVENT -> "固定事件"
    ScheduleEntryType.SHARED_EVENT -> "共享事件"
    ScheduleEntryType.WORK_SESSION -> "我的工作时段（仅我）"
}

private fun entryMarkers(entry: ScheduleEntry): String = buildList {
    add(entryTypeLabel(entry.type))
    if (entry.isRecurring) add("重复")
    entry.workspaceName?.let(::add)
    if (entry.workspaceArchived) add("已归档")
}.joinToString(" · ")

private fun deadlineLabel(deadline: ScheduleDeadline): String {
    val instant = deadline.at
    return when {
        deadline.date != null -> "${deadline.date}（仅日期）"
        instant != null -> "${formatMoment(instant)}（具体时间）"
        else -> "无截止"
    }
}

private fun formatMoment(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ScheduleZone)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

private fun clockOf(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ScheduleZone).toLocalTime().toString().take(5)
