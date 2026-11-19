package com.kaixu.domain.usecase.scheduling

import com.kaixu.data.datasource.FixedEventDataSource
import com.kaixu.data.datasource.TaskDataSource
import com.kaixu.data.datasource.WorkSessionDataSource
import com.kaixu.data.datasource.WorkspaceDataSource
import com.kaixu.domain.model.scheduling.Recurrence
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.model.tasks.TaskStatus
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.validation.scheduling.ShanghaiTime
import kotlin.math.abs

enum class AgendaKind { FIXED_EVENT, WORK_SESSION, TASK_DEADLINE }

/** time 是排序时刻；任务使用截止时刻，不代表开始执行。dateOnly 保留日期精度。 */
data class AgendaEntry(
    val key: String,
    val recordId: String,
    val kind: AgendaKind,
    val title: String,
    val time: Long,
    val endsAt: Long? = null,
    val dateOnly: Boolean = false,
    val workspace: String? = null,
    val completed: Boolean = false,
) {
    fun isOngoing(now: Long): Boolean = !completed && endsAt != null && time <= now && now < endsAt
}

data class AgendaWindow(val today: String, val start: Long, val endExclusive: Long) {
    companion object {
        fun around(today: String): AgendaWindow = AgendaWindow(
            today,
            ShanghaiTime.atLocalDate(ShanghaiTime.plusDays(today, -1), 0),
            ShanghaiTime.atLocalDate(ShanghaiTime.plusDays(today, 2), 0),
        )
    }
}

data class AgendaSnapshot(val window: AgendaWindow, val entries: List<AgendaEntry>) {
    /** 优先正在发生的安排，否则选择距离现在最近的未完成事项；没有时再回看历史。 */
    fun nearestIndex(now: Long): Int? {
        val candidates = entries.indices.filter { !entries[it].completed }.ifEmpty { entries.indices.toList() }
        return candidates.minWithOrNull(compareBy<Int>(
            { if (entries[it].isOngoing(now)) 0 else 1 },
            {
                val entry = entries[it]
                if (entry.isOngoing(now)) 0L else minOf(abs(entry.time - now), abs((entry.endsAt ?: entry.time) - now))
            },
            { entries[it].time },
        ))
    }
}

/** 全局日程的只读聚合：复用课次展开与本地访问边界，不创建重复任务或时段。 */
class AgendaUseCase(
    private val tasks: TaskDataSource,
    private val events: FixedEventDataSource,
    private val sessions: WorkSessionDataSource,
    private val workspaces: WorkspaceDataSource,
) {
    suspend operator fun invoke(ownerUserId: String, window: AgendaWindow): AgendaSnapshot {
        require(ownerUserId.isNotBlank())
        val allTasks = tasks.listTasks().filter { it.createdBy == ownerUserId || it.assigneeUserId == ownerUserId }
        val tasksById = allTasks.associateBy { it.id }
        val spaces = workspaces.listWorkspaces(status = null).filter { it.ownerUserId == ownerUserId }.associateBy { it.id }
        fun workspaceLabel(id: String?): String? = spaces[id]?.let {
            it.name + if (it.status == WorkspaceStatus.ARCHIVED) " · 已归档" else ""
        }
        val allEvents = events.listEvents().filter { it.createdBy == ownerUserId }
        val lookback = allEvents.filter { it.isRecurring }.maxOfOrNull { it.endsAt - it.startsAt } ?: 0L
        val occurrences = Recurrence.expand(
            allEvents, events.listOverridesIncludingDeleted().filter { it.createdBy == ownerUserId },
            window.start - lookback.coerceAtLeast(0), window.endExclusive,
        ).filter { it.startsAt < window.endExclusive && it.endsAt > window.start }

        val entries = buildList {
            occurrences.forEach { occurrence ->
                add(AgendaEntry(
                    key = "event:${occurrence.key}", recordId = occurrence.eventId,
                    kind = AgendaKind.FIXED_EVENT, title = occurrence.title,
                    time = occurrence.startsAt, endsAt = occurrence.endsAt,
                    workspace = workspaceLabel(occurrence.workspaceId),
                ))
            }
            sessions.listWorkSessionsInRange(ownerUserId, window.start, window.endExclusive)
                .filter { it.ownerUserId == ownerUserId && it.status != WorkSessionStatus.CANCELLED }
                .filter { it.startsAt < window.endExclusive && it.endsAt > window.start }
                .forEach { session ->
                    val task = tasksById[session.taskId]
                    add(AgendaEntry(
                        key = "session:${session.id}", recordId = session.id,
                        kind = AgendaKind.WORK_SESSION, title = task?.title ?: "个人工作时段",
                        time = session.startsAt, endsAt = session.endsAt,
                        workspace = workspaceLabel(task?.workspaceId),
                        completed = session.status == WorkSessionStatus.COMPLETED,
                    ))
                }
            allTasks.forEach { task ->
                val time = task.deadlineAt ?: task.deadlineDate?.let { date ->
                    // 仅日期的截止排在当天末尾，界面始终显示“当天截止”，不伪造执行时刻。
                    ShanghaiTime.atLocalDate(ShanghaiTime.plusDays(date, 1), 0) - 1
                } ?: return@forEach
                if (time >= window.start && time < window.endExclusive) {
                    add(AgendaEntry(
                        key = "task:${task.id}", recordId = task.id, kind = AgendaKind.TASK_DEADLINE,
                        title = task.title, time = time, dateOnly = task.deadlineAt == null,
                        workspace = workspaceLabel(task.workspaceId),
                        completed = task.status == TaskStatus.COMPLETED,
                    ))
                }
            }
        }.sortedWith(compareBy(AgendaEntry::time, { it.kind.ordinal }, AgendaEntry::key))
        return AgendaSnapshot(window, entries)
    }
}
