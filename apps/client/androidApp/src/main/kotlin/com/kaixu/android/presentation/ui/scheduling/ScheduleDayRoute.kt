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
import com.kaixu.core.platform.LocalZone
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.data.repository.DefaultFixedEventRepository
import com.kaixu.domain.model.scheduling.CalendarBlockKind
import com.kaixu.domain.model.scheduling.CalendarDayColumn
import com.kaixu.domain.model.scheduling.CalendarDaySlice
import com.kaixu.domain.model.scheduling.CalendarDeadlineItem
import com.kaixu.domain.model.scheduling.CalendarQuery
import com.kaixu.domain.model.scheduling.CalendarViewMode
import com.kaixu.domain.model.tasks.TaskDeadline
import com.kaixu.domain.usecase.scheduling.FixedEventUseCases
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException

private val ScheduleZone = ZoneId.of("Asia/Shanghai")
private val ScheduleLocalZone = LocalZone.of("Asia/Shanghai")
private val DayLabel = DateTimeFormatter.ofPattern("yyyy-MM-dd E", Locale.CHINA)

private sealed interface ScheduleDetail {
    data class SliceDetail(val slice: CalendarDaySlice) : ScheduleDetail
    data class DeadlineDetail(val deadline: CalendarDeadlineItem) : ScheduleDetail
}

private fun localAndroidFixedEventUseCases(sources: LocalDataSources): FixedEventUseCases = FixedEventUseCases(
    DefaultFixedEventRepository(
        source = sources.fixedEvents,
        workspaces = sources.workspaces,
        workSessions = sources.workSessions,
        tasks = sources.tasks,
        ownerUserId = LOCAL_ACTOR_ID,
        transaction = sources.transaction,
    ),
    defaultZone = ScheduleLocalZone,
)

/** Android 只读日程：按日期聚合本人固定事件、工作时段和可访问共享事件，无写入入口。 */
@Composable
fun ScheduleDayRoute(
    sources: LocalDataSources,
    onBack: () -> Unit,
    fixedEventUseCases: FixedEventUseCases? = null,
) {
    val activeUseCases = fixedEventUseCases ?: remember(sources) { localAndroidFixedEventUseCases(sources) }
    val revision by activeUseCases.revision.collectAsState()
    var selectedDate by remember { mutableStateOf(LocalDate.now(ScheduleZone)) }
    var retry by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<ScheduleDetail?>(null) }
    val result by produceState<Result<CalendarDayColumn>?>(null, selectedDate, retry, sources, activeUseCases, revision) {
        value = null
        value = try {
            val snapshot = activeUseCases.queryCalendar(
                query = CalendarQuery(
                    mode = CalendarViewMode.DAY,
                    anchorDate = selectedDate.toString(),
                ),
                zone = ScheduleLocalZone,
            )
            Result.success(snapshot.days.single())
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
                val day = snapshot.getOrThrow()
                if (day.slices.isEmpty() && day.deadlines.isEmpty()) {
                    Text("这一天没有安排。", modifier = Modifier.testTag("android-schedule-empty"))
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (day.slices.isNotEmpty()) {
                            item(key = "entries-label") { Text("执行时间", style = MaterialTheme.typography.labelLarge) }
                            items(day.slices, key = { "entry-${it.sliceKey}" }) { slice ->
                                ListItem(
                                    headlineContent = { Text(slice.block.title) },
                                    supportingContent = {
                                        Column {
                                            Text("${clockOf(slice.sliceStartMillis)} — ${clockOf(slice.sliceEndMillis)}")
                                            Text(sliceMarkers(slice))
                                        }
                                    },
                                    modifier = Modifier
                                        .clickable { detail = ScheduleDetail.SliceDetail(slice) }
                                        .testTag("android-schedule-entry-${slice.block.key}"),
                                )
                            }
                        }
                        if (day.deadlines.isNotEmpty()) {
                            item(key = "deadlines-label") { Text("截止要求", style = MaterialTheme.typography.labelLarge) }
                            items(day.deadlines, key = { "deadline-${it.key}" }) { deadline ->
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
                        is ScheduleDetail.SliceDetail -> current.slice.block.title
                        is ScheduleDetail.DeadlineDetail -> "截止要求 · ${current.deadline.title}"
                    },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (current) {
                        is ScheduleDetail.SliceDetail -> {
                            val block = current.slice.block
                            Text("类型：${blockKindLabel(block.kind)}")
                            Text("执行时间：${clockOf(current.slice.sliceStartMillis)} — ${clockOf(current.slice.sliceEndMillis)}")
                            block.workspaceName?.let { Text("所属工作区：$it") }
                            if (block.workspaceArchived) Text("工作区已归档，安排只读")
                            if (block.isRecurring) Text("重复课次")
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

private fun blockKindLabel(kind: CalendarBlockKind): String = when (kind) {
    CalendarBlockKind.FIXED_EVENT -> "固定事件"
    CalendarBlockKind.SHARED_EVENT -> "共享事件"
    CalendarBlockKind.WORK_SESSION -> "我的工作时段（仅我）"
}

private fun sliceMarkers(slice: CalendarDaySlice): String = buildList {
    val block = slice.block
    add(blockKindLabel(block.kind))
    if (block.isRecurring) add("重复")
    block.workspaceName?.let(::add)
    if (block.workspaceArchived) add("已归档")
}.joinToString(" · ")

private fun deadlineLabel(deadline: CalendarDeadlineItem): String = when (val value = deadline.deadline) {
    is TaskDeadline.DateOnly -> "${value.date}（仅日期）"
    is TaskDeadline.ExactTime -> "${formatMoment(value.epochMillis)}（具体时间）"
    TaskDeadline.None -> "无截止"
}

private fun formatMoment(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ScheduleZone)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

private fun clockOf(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ScheduleZone).toLocalTime().toString().take(5)
