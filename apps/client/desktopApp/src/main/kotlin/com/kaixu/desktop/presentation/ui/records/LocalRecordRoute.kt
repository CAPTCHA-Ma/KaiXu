package com.kaixu.desktop.presentation.ui.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kaixu.core.platform.LocalZone
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.domain.model.tasks.TaskStatus
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.FixedEventTarget
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.desktop.presentation.ui.scheduling.EventEditorTarget
import com.kaixu.desktop.presentation.ui.scheduling.FixedEventEditorDialog
import com.kaixu.desktop.presentation.ui.scheduling.localFixedEventUseCases
import com.kaixu.desktop.presentation.ui.workspaces.localMoment
import com.kaixu.domain.usecase.scheduling.FixedEventUseCases
import com.kaixu.domain.usecase.tasks.TaskUseCases
import kotlinx.coroutines.CancellationException

enum class RecordKind { TASK, EVENT, SESSION }
data class LocalRecordTarget(val kind: RecordKind, val id: String)
private data class RecordDetails(
    val title: String,
    val fields: List<Pair<String, String>>,
    val editableTask: Task? = null,
    val editableEvent: FixedEvent? = null,
)
private data class RecordState(val loading: Boolean = true, val details: RecordDetails? = null, val error: String? = null)

/** 全局和工作区入口共用同一原记录详情和编辑入口。 */
@Composable
fun LocalRecordRoute(
    target: LocalRecordTarget,
    sources: LocalDataSources,
    onBack: () -> Unit,
    taskUseCases: TaskUseCases? = null,
    fixedEventUseCases: FixedEventUseCases? = null,
) {
    val activeTaskUseCases = taskUseCases ?: remember(sources) { localTaskUseCases(sources) }
    val activeFixedEventUseCases = fixedEventUseCases ?: remember(sources) { localFixedEventUseCases(sources) }
    var retry by remember(target) { mutableIntStateOf(0) }
    var editing by remember(target) { mutableStateOf(false) }
    val state by produceState(RecordState(), target, retry, activeTaskUseCases, activeFixedEventUseCases) {
        value = RecordState()
        try {
            val details = when (target.kind) {
                RecordKind.TASK -> activeTaskUseCases.getDetail(target.id)?.let { detail ->
                    val task = detail.task
                    val workspace = detail.workspace
                    RecordDetails(task.title, listOfNotNull(
                        task.description?.let { "说明" to it },
                        "状态" to when (task.status) {
                            TaskStatus.TODO -> "待办"
                            TaskStatus.IN_PROGRESS -> "进行中"
                            TaskStatus.COMPLETED -> "已完成"
                        },
                        "截止要求" to (task.deadlineDate ?: task.deadlineAt?.let(::localMoment) ?: "无截止"),
                        "所属工作区" to (workspace?.name ?: if (task.workspaceId == null) "独立任务" else "关联工作区暂不可用"),
                    ), editableTask = task.takeIf { detail.canEdit })
                }
                RecordKind.EVENT -> activeFixedEventUseCases.getDetail(
                    FixedEventTarget.SingleOrSeries(target.id),
                    zone = LocalZone.of("Asia/Shanghai"),
                )?.let { detail ->
                    val event = detail.record
                    val workspace = detail.workspace
                    val repeatDays = detail.series?.repeatWeekdays ?: event.repeatWeekdays
                    val repeatUntil = detail.series?.repeatUntil ?: event.repeatUntil
                    RecordDetails(event.title, listOfNotNull(
                        "记录类型" to if (detail.isSharedReadOnly) "共享事件（只读）" else "固定事件",
                        "执行时间" to "${localMoment(detail.occurrenceStart)} — ${localMoment(detail.occurrenceEnd)}",
                        event.description?.let { "说明" to it },
                        "所属工作区" to (workspace?.name ?: if (event.workspaceId == null) "独立事件" else "关联工作区暂不可用"),
                        repeatDays?.let { days ->
                            "重复" to days.sorted().joinToString("、") { "周" + "一二三四五六日"[it - 1] } +
                                "，至 $repeatUntil"
                        },
                        event.reminderOffsetMinutes?.let { "站内提醒" to "提前 $it 分钟" },
                        detail.workspaceArchived.takeIf { it }?.let { "工作区状态" to "已归档，事件只读" },
                    ), editableEvent = event.takeIf {
                        detail.canEdit && !detail.supportsScopeSelection && !event.isOccurrenceOverride
                    })
                }
                RecordKind.SESSION -> sources.workSessions.findWorkSessionById(target.id)
                    ?.takeIf { it.ownerUserId == LOCAL_ACTOR_ID }?.let { session ->
                        val task = session.taskId?.let { sources.tasks.findTaskById(it) }
                        val workspace = task?.workspaceId?.let { sources.workspaces.findWorkspaceById(it) }
                        RecordDetails("我的工作时段", listOfNotNull(
                            "执行时间" to "${localMoment(session.startsAt)} — ${localMoment(session.endsAt)}",
                            "状态" to when (session.status) {
                                WorkSessionStatus.PLANNED -> "已安排"
                                WorkSessionStatus.COMPLETED -> "已完成"
                                WorkSessionStatus.CANCELLED -> "已取消"
                            },
                            session.note?.let { "说明" to it },
                            "截止要求" to (task?.let {
                                it.deadlineDate ?: it.deadlineAt?.let(::localMoment) ?: "无截止"
                            } ?: "无关联任务"),
                            task?.let { "关联任务" to it.title },
                            "所属工作区" to (workspace?.name ?: when {
                                task == null -> "无关联任务"
                                task.workspaceId == null -> "独立任务"
                                else -> "关联工作区暂不可用"
                            }),
                            (workspace?.status == WorkspaceStatus.ARCHIVED).takeIf { it }?.let {
                                "工作区状态" to "已归档，安排只读"
                            },
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
                if (details.editableTask != null || details.editableEvent != null) {
                    OutlinedButton(onClick = { editing = true }, modifier = Modifier.testTag("edit-record")) {
                        Text(if (details.editableTask != null) "编辑任务" else "编辑固定事件")
                    }
                }
                details.fields.forEach { (label, value) ->
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    Text(value)
                }
            }
        }
    }
    if (editing) {
        val onSaved: () -> Unit = { editing = false; retry++ }
        val onDismiss: () -> Unit = { editing = false }
        state.details?.editableTask?.let { TaskRecordEditor(it, sources, onDismiss, onSaved, taskUseCases = activeTaskUseCases) }
        state.details?.editableEvent?.let { event ->
            FixedEventEditorDialog(
                sources = sources,
                target = EventEditorTarget(event, event.startsAt, event.endsAt),
                onDismiss = onDismiss,
                onSaved = onSaved,
                onDeleted = onSaved,
                fixedEventUseCases = activeFixedEventUseCases,
            )
        }
    }
}
