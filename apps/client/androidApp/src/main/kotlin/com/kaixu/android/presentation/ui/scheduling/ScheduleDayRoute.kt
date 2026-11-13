package com.kaixu.android.presentation.ui.scheduling

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
import com.kaixu.domain.model.scheduling.Recurrence
import com.kaixu.domain.model.scheduling.ScheduleOccurrence
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException

private val ScheduleZone = ZoneId.of("Asia/Shanghai")
private val DayLabel = DateTimeFormatter.ofPattern("yyyy-MM-dd E", Locale.CHINA)

/** Android 只读日程：展示本人固定事件与重复课次，不提供任何写入入口。 */
@Composable
fun ScheduleDayRoute(sources: LocalDataSources, onBack: () -> Unit) {
    var selectedDate by remember { mutableStateOf(LocalDate.now(ScheduleZone)) }
    var retry by remember { mutableIntStateOf(0) }
    val result by produceState<Result<List<ScheduleOccurrence>>?>(null, selectedDate, retry, sources) {
        value = null
        val start = selectedDate.atStartOfDay(ScheduleZone).toInstant().toEpochMilli()
        val end = selectedDate.plusDays(1).atStartOfDay(ScheduleZone).toInstant().toEpochMilli()
        value = try {
            val events = sources.fixedEvents.listEvents().filter { it.createdBy == LOCAL_ACTOR_ID }
            val overrides = sources.fixedEvents.listOverridesIncludingDeleted()
            Result.success(Recurrence.expand(events, overrides, start, end))
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
            Text(DayLabel.format(selectedDate), style = MaterialTheme.typography.titleMedium)
        }

        val snapshot = result
        when {
            snapshot == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            snapshot.isFailure -> {
                Text("读取固定事件失败。", color = MaterialTheme.colorScheme.error)
                Button(onClick = { retry++ }) { Text("重试") }
            }
            snapshot.getOrThrow().isEmpty() -> Text("这一天没有固定事件。", modifier = Modifier.testTag("android-schedule-empty"))
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(snapshot.getOrThrow(), key = { it.key }) { occurrence ->
                    ListItem(
                        headlineContent = { Text(occurrence.title) },
                        supportingContent = {
                            Text(
                                "${clockOf(occurrence.startsAt)} — ${clockOf(occurrence.endsAt)}" +
                                    if (occurrence.isRecurring) "  ·  重复" else "",
                            )
                        },
                        modifier = Modifier.testTag("android-schedule-event-${occurrence.key}"),
                    )
                }
            }
        }
    }
}

private fun clockOf(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ScheduleZone).toLocalTime().toString().take(5)
