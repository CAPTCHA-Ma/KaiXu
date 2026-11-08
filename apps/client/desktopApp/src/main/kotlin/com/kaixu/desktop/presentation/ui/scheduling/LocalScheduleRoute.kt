package com.kaixu.desktop.presentation.ui.scheduling

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kaixu.data.datasource.FixedEventDataSource
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.desktop.presentation.ui.workspaces.localMoment
import com.kaixu.domain.model.scheduling.FixedEvent
import kotlinx.coroutines.CancellationException

@Composable
internal fun LocalScheduleRoute(events: FixedEventDataSource, onOpenEvent: (String) -> Unit) {
    var retry by remember { mutableIntStateOf(0) }
    val result by produceState<Result<List<FixedEvent>>?>(null, events, retry) {
        value = null
        value = try {
            Result.success(events.listEvents().filter { it.createdBy == LOCAL_ACTOR_ID })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("固定事件", style = MaterialTheme.typography.headlineMedium)
        Text("独立本地", style = MaterialTheme.typography.bodySmall)
        val snapshot = result
        when {
            snapshot == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            snapshot.isFailure -> {
                Text("读取固定事件失败。", color = MaterialTheme.colorScheme.error)
                Button(onClick = { retry++ }) { Text("重试") }
            }
            else -> {
                val records = snapshot.getOrThrow()
                if (records.isEmpty()) Text("暂无固定事件")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(records, key = { it.id }) { event ->
                        OutlinedButton(
                            onClick = { onOpenEvent(event.id) },
                            modifier = Modifier.fillMaxWidth().testTag("schedule-event-${event.id}"),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(event.title)
                                Text("${localMoment(event.startsAt)} 至 ${localMoment(event.endsAt)}")
                            }
                        }
                    }
                }
            }
        }
    }
}
