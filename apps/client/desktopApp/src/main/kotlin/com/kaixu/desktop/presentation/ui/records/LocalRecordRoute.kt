package com.kaixu.desktop.presentation.ui.records

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
import com.kaixu.domain.model.tasks.TaskStatus
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.desktop.presentation.ui.workspaces.localMoment
import kotlinx.coroutines.CancellationException

enum class RecordKind { TASK, EVENT, SESSION }
data class LocalRecordTarget(val kind: RecordKind, val id: String)
private data class RecordDetails(val title: String, val fields: List<Pair<String, String>>)
private data class RecordState(val loading: Boolean = true, val details: RecordDetails? = null, val error: String? = null)

/** 全局任务和工作区入口使用相同的按 ID 读取详情。 */
@Composable
fun LocalRecordRoute(target: LocalRecordTarget, sources: LocalDataSources, onBack: () -> Unit) {
    var retry by remember(target) { mutableIntStateOf(0) }
    val state by produceState(RecordState(), target, retry) {
        value = RecordState()
        try {
            val details = when (target.kind) {
                RecordKind.TASK -> sources.tasks.findTaskById(target.id)?.let { task ->
                    RecordDetails(task.title, listOfNotNull(
                        task.description?.let { "说明" to it },
                        "状态" to when (task.status) {
                            TaskStatus.TODO -> "待办"
                            TaskStatus.IN_PROGRESS -> "进行中"
                            TaskStatus.COMPLETED -> "已完成"
                        },
                        "截止" to (task.deadlineDate ?: task.deadlineAt?.let(::localMoment) ?: "无截止"),
                        "所属工作区" to if (task.workspaceId == null) "独立任务" else
                            (task.workspaceId.let { sources.workspaces.findWorkspaceById(requireNotNull(it))?.name } ?: "关联工作区暂不可用"),
                    ))
                }
                RecordKind.EVENT -> sources.fixedEvents.findEventById(target.id)?.let { event ->
                    RecordDetails(event.title, listOfNotNull(
                        "开始" to localMoment(event.startsAt), "结束" to localMoment(event.endsAt),
                        event.description?.let { "说明" to it },
                        "所属工作区" to if (event.workspaceId == null) "独立事件" else
                            (event.workspaceId.let { sources.workspaces.findWorkspaceById(requireNotNull(it))?.name } ?: "关联工作区暂不可用"),
                    ))
                }
                RecordKind.SESSION -> sources.workSessions.findWorkSessionById(target.id)
                    ?.takeIf { it.ownerUserId == LOCAL_ACTOR_ID }?.let { session ->
                        RecordDetails("我的工作时段", listOfNotNull(
                            "开始" to localMoment(session.startsAt), "结束" to localMoment(session.endsAt),
                            "状态" to when (session.status) {
                                WorkSessionStatus.PLANNED -> "已安排"
                                WorkSessionStatus.COMPLETED -> "已完成"
                                WorkSessionStatus.CANCELLED -> "已取消"
                            },
                            session.note?.let { "说明" to it },
                            session.taskId?.let { id -> sources.tasks.findTaskById(id)?.title?.let { "关联任务" to it } },
                        ))
                    }
            }
            value = RecordState(loading = false, details = details)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = RecordState(loading = false, error = "读取详情失败，请重试。")
        }
    }
    Column(Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) { Text("← 返回列表") }
        when {
            state.loading -> CircularProgressIndicator()
            state.error != null -> {
                Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                Button(onClick = { retry++ }) { Text("重试详情") }
            }
            state.details == null -> Text("记录已不可用，请返回列表刷新。")
            else -> state.details?.let { details ->
                Text(details.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("record-title"))
                details.fields.forEach { (label, value) ->
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    Text(value)
                }
            }
        }
    }
}
