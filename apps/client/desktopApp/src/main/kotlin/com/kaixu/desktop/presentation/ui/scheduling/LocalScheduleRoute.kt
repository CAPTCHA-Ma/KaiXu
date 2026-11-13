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
import com.kaixu.domain.model.scheduling.ScheduleOccurrence
import com.kaixu.domain.repository.SchedulingTransaction
import com.kaixu.domain.usecase.scheduling.FixedEventSchedule
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val ScheduleZone = ZoneId.of("Asia/Shanghai")
private val DayLabelFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd E", Locale.CHINA)

internal enum class ScheduleMode { DAY, WEEK }

/** 桌面日程页：日/周视图、日期切换与固定事件的新建/编辑/删除入口。 */
@Composable
internal fun LocalScheduleRoute(sources: LocalDataSources) {
    val schedule = remember(sources) {
        FixedEventSchedule(
            sources.fixedEvents, sources.workSessions, sources.workspaces, LOCAL_ACTOR_ID,
            object : SchedulingTransaction {
                override suspend fun <T> run(block: suspend () -> T): T = sources.writeTransaction(block)
            },
        )
    }
    var mode by remember { mutableStateOf(ScheduleMode.DAY) }
    var selectedDate by remember { mutableStateOf(LocalDate.now(ScheduleZone)) }
    var reload by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var editorTarget by remember { mutableStateOf<EventEditorTarget?>(null) }
    var opening by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val window = remember(mode, selectedDate) { windowFor(mode, selectedDate) }
    val state by produceState<Result<List<ScheduleOccurrence>>?>(null, window, reload) {
        value = null
        value = try {
            Result.success(schedule.occurrences(window.first, window.second))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    fun openOccurrence(occurrence: ScheduleOccurrence) {
        if (opening) return
        opening = true
        scope.launch {
            try {
                val rowId = occurrence.seriesId ?: occurrence.eventId
                val row = sources.fixedEvents.findEventById(rowId)
                if (row != null) {
                    editorTarget = EventEditorTarget(
                        event = row,
                        occurrenceStart = occurrence.startsAt,
                        occurrenceEnd = occurrence.endsAt,
                        originalDate = occurrence.originalDate,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 读取失败时保持列表不动，用户可重新点击或刷新。
            } finally {
                opening = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("日程", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Button(
                onClick = { creating = true },
                modifier = Modifier.testTag("schedule-create"),
            ) { Text("新建固定事件") }
        }
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
        Text(
            "固定事件与重复课次；重复事件标记为「重复」。",
            style = MaterialTheme.typography.bodySmall,
        )

        val snapshot = state
        when {
            snapshot == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            snapshot.isFailure -> {
                Text("读取固定事件失败。", color = MaterialTheme.colorScheme.error)
                Button(onClick = { reload++ }) { Text("重试") }
            }
            else -> {
                val occurrences = snapshot.getOrThrow()
                if (mode == ScheduleMode.DAY) {
                    DayOccurrences(
                        date = selectedDate,
                        occurrences = occurrences.filter { localDateOf(it.startsAt) == selectedDate },
                        onOpen = ::openOccurrence,
                    )
                } else {
                    WeekOccurrences(
                        startDate = selectedDate.with(DayOfWeek.MONDAY),
                        occurrences = occurrences,
                        onOpen = ::openOccurrence,
                    )
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
    editorTarget?.let { target ->
        FixedEventEditorDialog(
            sources = sources,
            target = target,
            onDismiss = { editorTarget = null },
            onSaved = { editorTarget = null; reload++ },
            onDeleted = { editorTarget = null; reload++ },
        )
    }
}

@Composable
private fun DayOccurrences(
    date: LocalDate,
    occurrences: List<ScheduleOccurrence>,
    onOpen: (ScheduleOccurrence) -> Unit,
) {
    if (occurrences.isEmpty()) {
        Text("这一天没有固定事件。", modifier = Modifier.testTag("schedule-empty"))
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(occurrences, key = { it.key }) { occurrence ->
            OccurrenceRow(occurrence, onOpen)
        }
    }
}

@Composable
private fun WeekOccurrences(
    startDate: LocalDate,
    occurrences: List<ScheduleOccurrence>,
    onOpen: (ScheduleOccurrence) -> Unit,
) {
    val byDate = occurrences.groupBy { localDateOf(it.startsAt) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for (offset in 0 until 7) {
            val date = startDate.plusDays(offset.toLong())
            val dayItems = byDate[date].orEmpty()
            item(key = "day-$date") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("schedule-day-$date")) {
                    Text(DayLabelFormat.format(date), style = MaterialTheme.typography.titleMedium)
                    if (dayItems.isEmpty()) {
                        Text("无安排", style = MaterialTheme.typography.bodySmall)
                    } else {
                        dayItems.forEach { occurrence -> OccurrenceRow(occurrence, onOpen) }
                    }
                }
            }
        }
    }
}

@Composable
private fun OccurrenceRow(occurrence: ScheduleOccurrence, onOpen: (ScheduleOccurrence) -> Unit) {
    OutlinedButton(
        onClick = { onOpen(occurrence) },
        modifier = Modifier.fillMaxWidth().testTag("schedule-event-${occurrence.key}"),
    ) {
        Column(Modifier.weight(1f)) {
            Text(occurrence.title)
            Text(
                "${clockOf(occurrence.startsAt)} — ${clockOf(occurrence.endsAt)}" +
                    if (occurrence.isRecurring) "  ·  重复" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

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
