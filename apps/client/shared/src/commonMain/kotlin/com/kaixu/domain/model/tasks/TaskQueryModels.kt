package com.kaixu.domain.model.tasks

import com.kaixu.core.platform.CalendarDate
import com.kaixu.core.platform.LocalZone
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.validation.tasks.TaskInput

sealed interface TaskWorkspaceFilter {
    data object All : TaskWorkspaceFilter
    data object Standalone : TaskWorkspaceFilter
    data class Workspace(val workspaceId: String) : TaskWorkspaceFilter {
        init {
            require(workspaceId.isNotBlank()) { "工作区 ID 不能为空白" }
        }
    }
}

fun TaskWorkspaceFilter.matches(workspaceId: String?): Boolean = when (this) {
    TaskWorkspaceFilter.All -> true
    TaskWorkspaceFilter.Standalone -> workspaceId == null
    is TaskWorkspaceFilter.Workspace -> workspaceId == this.workspaceId
}

sealed interface TaskDeadlineFilter {
    data object All : TaskDeadlineFilter
    data object Today : TaskDeadlineFilter
    data object UpcomingSevenDays : TaskDeadlineFilter
    data object Overdue : TaskDeadlineFilter
    data object NoDeadline : TaskDeadlineFilter
    data class OnDate(val date: String) : TaskDeadlineFilter {
        init {
            require(TaskInput.isValidDate(date)) { "筛选日期必须是有效的 YYYY-MM-DD" }
        }
    }
}

fun TaskDeadlineFilter.matches(
    task: Task,
    nowMillis: Long,
    zone: LocalZone = LocalZone.systemDefault(),
): Boolean {
    val today = zone.localDateOf(nowMillis)
    val taskDeadline = task.deadline
    return when (this) {
        TaskDeadlineFilter.All -> true
        TaskDeadlineFilter.NoDeadline -> taskDeadline == TaskDeadline.None
        TaskDeadlineFilter.Overdue -> taskDeadline.isPastAt(nowMillis, zone)
        TaskDeadlineFilter.Today -> taskDeadline.localDateOrNull(zone) == today
        is TaskDeadlineFilter.OnDate -> taskDeadline.localDateOrNull(zone) == date
        TaskDeadlineFilter.UpcomingSevenDays -> {
            val endDateExclusive = CalendarDate.plusDays(today, 7)
            val endMillisExclusive = zone.startOfDayMillis(endDateExclusive)
            when (taskDeadline) {
                TaskDeadline.None -> false
                is TaskDeadline.DateOnly ->
                    CalendarDate.compareDates(taskDeadline.date, today) >= 0 &&
                        CalendarDate.compareDates(taskDeadline.date, endDateExclusive) < 0
                is TaskDeadline.ExactTime ->
                    taskDeadline.epochMillis >= nowMillis && taskDeadline.epochMillis < endMillisExclusive
            }
        }
    }
}

enum class TaskSortOrder {
    /** 按截止时间升序，无截止排在末尾。 */
    DEADLINE_ASC,
    /** 按优先级从高到低（HIGH → NORMAL → LOW），同优先级按截止时间升序。 */
    PRIORITY_DESC,
    /** 保持最近创建/更新的默认顺序。 */
    DEFAULT_RECENT,
}

data class TaskQuery(
    val workspaceFilter: TaskWorkspaceFilter = TaskWorkspaceFilter.All,
    val statuses: Set<TaskStatus> = setOf(TaskStatus.TODO, TaskStatus.IN_PROGRESS),
    val deadlineFilter: TaskDeadlineFilter = TaskDeadlineFilter.All,
    val keyword: String = "",
    val sortOrder: TaskSortOrder = TaskSortOrder.DEADLINE_ASC,
)

enum class TaskScheduleState {
    UNSCHEDULED,
    HAS_SESSIONS,
}

data class TaskListItem(
    val task: Task,
    val workspaceName: String? = null,
    val workspaceArchived: Boolean = false,
    val canEdit: Boolean = true,
    val scheduleState: TaskScheduleState = TaskScheduleState.UNSCHEDULED,
    val activeSessionCount: Int = 0,
    val isOverdue: Boolean = false,
)

data class TaskListCounts(
    val totalCount: Int = 0,
    val incompleteCount: Int = 0,
    val completedCount: Int = 0,
    val overdueCount: Int = 0,
    val noDeadlineCount: Int = 0,
    val unscheduledCount: Int = 0,
)

data class TaskListSnapshot(
    val query: TaskQuery,
    val items: List<TaskListItem>,
    val counts: TaskListCounts,
)

data class TaskDetail(
    val task: Task,
    val workspace: Workspace? = null,
    val canEdit: Boolean,
    val workSessions: List<WorkSession> = emptyList(),
    val futurePlannedSessions: List<WorkSession> = emptyList(),
    val isOverdue: Boolean = false,
)
