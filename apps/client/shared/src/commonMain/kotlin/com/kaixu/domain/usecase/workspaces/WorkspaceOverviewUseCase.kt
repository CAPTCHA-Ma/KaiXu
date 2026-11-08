package com.kaixu.domain.usecase.workspaces

import com.kaixu.data.datasource.FixedEventDataSource
import com.kaixu.data.datasource.TaskDataSource
import com.kaixu.data.datasource.WorkSessionDataSource
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.WorkSession
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
            .filter { it.workspaceId == workspaceId }
            .sortedWith(compareBy(FixedEvent::startsAt, FixedEvent::id))
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
            fixedEvents = workspaceEvents,
            workSessions = personalSessions,
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
