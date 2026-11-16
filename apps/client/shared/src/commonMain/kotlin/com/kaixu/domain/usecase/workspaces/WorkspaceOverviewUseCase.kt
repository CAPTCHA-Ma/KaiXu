package com.kaixu.domain.usecase.workspaces

import com.kaixu.data.datasource.FixedEventDataSource
import com.kaixu.data.datasource.TaskDataSource
import com.kaixu.data.datasource.WorkSessionDataSource
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.Recurrence
import com.kaixu.domain.model.scheduling.ScheduleOccurrence
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.validation.scheduling.ShanghaiTime
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.tasks.TaskStatus

enum class WorkspaceDeadlineFilter { ALL, OVERDUE, NEXT_SEVEN_DAYS, NO_DEADLINE }

/**
 * 桌面使用 Asia/Shanghai 构造日期和对应时刻。
 * 近期为包含今天的七个自然日，到 today.plusDays(7) 的上海零点为止。
 * 日期截止保留日期语义，精确截止从 nowMillis 起计算。
 */
data class WorkspaceDeadlineWindow(
    val todayDate: String,
    val nowMillis: Long,
    val upcomingEndDateExclusive: String,
    val upcomingEndMillisExclusive: Long,
)

data class WorkspaceOverview(
    val workspaceId: String,
    val tasks: List<Task>,
    val allTaskCount: Int,
    val incompleteTaskCount: Int,
    val completedTaskCount: Int,
    val upcomingTasks: List<Task>,
    val fixedEvents: List<FixedEvent>,
    val workSessions: List<WorkSession>,
    val recurringOccurrences: List<ScheduleOccurrence> = emptyList(),
)

/** 按当前本地空间查询原记录，统计和日程不受任务列表筛选影响。读取失败交由页面呈现及重试。 */
class WorkspaceOverviewUseCase(
    private val tasks: TaskDataSource,
    private val events: FixedEventDataSource,
    private val sessions: WorkSessionDataSource,
) {
    suspend operator fun invoke(
        workspaceId: String,
        ownerUserId: String,
        window: WorkspaceDeadlineWindow,
        status: TaskStatus? = null,
        deadline: WorkspaceDeadlineFilter = WorkspaceDeadlineFilter.ALL,
    ): WorkspaceOverview {
        require(workspaceId.isNotBlank()) { "请选择工作区" }
        require(ownerUserId.isNotBlank()) { "缺少当前空间的本人身份" }

        val workspaceTasks = tasks.listTasksByWorkspace(workspaceId).filter { it.workspaceId == workspaceId }
        val taskIds = workspaceTasks.mapTo(mutableSetOf()) { it.id }
        val workspaceEvents = events.listEventsByWorkspace(workspaceId)
            .filter { it.workspaceId == workspaceId && it.seriesId == null && it.repeatWeekdays == null }
        // 一次课次可移动到另一个工作区，因此先展开本人所有序列，再按课次的最终归属过滤。
        val allEvents = events.listEvents().filter { it.createdBy == ownerUserId }
        val overrides = events.listOverridesIncludingDeleted().filter { it.createdBy == ownerUserId }
        val windowStart = ShanghaiTime.atLocalDate(window.todayDate, 0L)
        // 包含从窗口前一天或更早开始、但跨入窗口的长时课次。
        val longestDuration = allEvents.asSequence().filter { it.repeatWeekdays != null && it.endsAt > it.startsAt }
            .map { it.endsAt - it.startsAt }.maxOrNull() ?: 0L
        val expansionStart = if (windowStart < Long.MIN_VALUE + longestDuration) Long.MIN_VALUE else windowStart - longestDuration
        val expanded = Recurrence.expand(
            allEvents, overrides,
            expansionStart, window.upcomingEndMillisExclusive,
        )
        val records = allEvents.associateBy { it.id }
        // 例外可从窗口外移入；仅遍历窗口内的原定日期时，展开器不会返回这种课次。
        val movedIntoWindow = overrides.mapNotNull { override ->
            val seriesId = override.seriesId ?: return@mapNotNull null
            val date = override.originalDate ?: return@mapNotNull null
            if (override.isCancelled || (date >= window.todayDate && date < window.upcomingEndDateExclusive)) return@mapNotNull null
            val series = records[seriesId] ?: return@mapNotNull null
            val weekdays = series.repeatWeekdays ?: return@mapNotNull null
            val until = series.repeatUntil ?: return@mapNotNull null
            if (date < ShanghaiTime.localDateOf(series.startsAt) || date > until || ShanghaiTime.isoWeekday(date) !in weekdays) return@mapNotNull null
            ScheduleOccurrence(
                key = "$seriesId@$date", eventId = override.id, title = override.title,
                startsAt = override.startsAt, endsAt = override.endsAt, description = override.description,
                workspaceId = override.workspaceId, createdBy = override.createdBy,
                reminderOffsetMinutes = override.reminderOffsetMinutes, seriesId = seriesId, originalDate = date,
            )
        }
        val recurring = (expanded + movedIntoWindow)
            .filter { it.seriesId != null && it.workspaceId == workspaceId &&
                it.startsAt < window.upcomingEndMillisExclusive && it.endsAt > windowStart }
            .distinctBy { it.key }.sortedWith(compareBy({ it.startsAt }, { it.key }))
        val occurrenceEvents = recurring.mapNotNull { occurrence ->
            records[occurrence.eventId]?.copy(
                title = occurrence.title, startsAt = occurrence.startsAt, endsAt = occurrence.endsAt,
                description = occurrence.description, workspaceId = occurrence.workspaceId,
            )
        }
        val personalSessions = sessions.listWorkSessions()
            .filter { it.ownerUserId == ownerUserId && it.taskId != null && it.taskId in taskIds }
            .sortedWith(compareBy(WorkSession::startsAt, WorkSession::id))
        val completedCount = workspaceTasks.count { it.status == TaskStatus.COMPLETED }

        return WorkspaceOverview(
            workspaceId = workspaceId,
            tasks = workspaceTasks.filter {
                (status == null || it.status == status) && matchesDeadline(it, deadline, window)
            },
            allTaskCount = workspaceTasks.size,
            incompleteTaskCount = workspaceTasks.size - completedCount,
            completedTaskCount = completedCount,
            upcomingTasks = workspaceTasks.filter {
                it.status != TaskStatus.COMPLETED && matchesDeadline(it, WorkspaceDeadlineFilter.NEXT_SEVEN_DAYS, window)
            },
            fixedEvents = (workspaceEvents + occurrenceEvents).sortedWith(compareBy(FixedEvent::startsAt, FixedEvent::id)),
            workSessions = personalSessions,
            recurringOccurrences = recurring,
        )
    }

    // 截止条件独立于状态条件，已完成任务也可按原截止日期查询。
    private fun matchesDeadline(task: Task, filter: WorkspaceDeadlineFilter, window: WorkspaceDeadlineWindow): Boolean =
        when (filter) {
            WorkspaceDeadlineFilter.ALL -> true
            WorkspaceDeadlineFilter.NO_DEADLINE -> task.deadlineDate == null && task.deadlineAt == null
            WorkspaceDeadlineFilter.OVERDUE -> {
                val date = task.deadlineDate
                val instant = task.deadlineAt
                (date != null && date < window.todayDate) || (instant != null && instant < window.nowMillis)
            }
            WorkspaceDeadlineFilter.NEXT_SEVEN_DAYS -> {
                val date = task.deadlineDate
                val instant = task.deadlineAt
                (date != null && date >= window.todayDate && date < window.upcomingEndDateExclusive) ||
                    (instant != null && instant >= window.nowMillis && instant < window.upcomingEndMillisExclusive)
            }
        }
}
