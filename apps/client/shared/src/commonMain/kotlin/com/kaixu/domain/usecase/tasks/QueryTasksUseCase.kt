package com.kaixu.domain.usecase.tasks

import com.kaixu.core.platform.LocalZone
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.model.tasks.TaskDeadline
import com.kaixu.domain.model.tasks.TaskDetail
import com.kaixu.domain.model.tasks.TaskListCounts
import com.kaixu.domain.model.tasks.TaskListItem
import com.kaixu.domain.model.tasks.TaskListSnapshot
import com.kaixu.domain.model.tasks.TaskPriority
import com.kaixu.domain.model.tasks.TaskQuery
import com.kaixu.domain.model.tasks.TaskScheduleState
import com.kaixu.domain.model.tasks.TaskSortOrder
import com.kaixu.domain.model.tasks.TaskStatus
import com.kaixu.domain.model.tasks.deadline
import com.kaixu.domain.model.tasks.isOverdueAt
import com.kaixu.domain.model.tasks.matches
import com.kaixu.domain.model.tasks.sortKeyMillis
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.repository.TaskRepository
import com.kaixu.domain.validation.tasks.TaskInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * 查询并筛选本人可见任务列表，附带工作区归档、可编辑性、排期状态与截止逾期标识。
 * 统计口径保持工作区筛选范围内稳定，不随状态/截止/关键词筛选而缩水。
 */
class QueryTasksUseCase(
    private val repository: TaskRepository,
    private val defaultZone: LocalZone = LocalZone.systemDefault(),
) {
    suspend operator fun invoke(
        query: TaskQuery = TaskQuery(),
        nowMillis: Long = currentEpochMillis(),
        zone: LocalZone = defaultZone,
    ): TaskListSnapshot {
        val workspacesById = repository.listWorkspaces(status = null).associateBy { it.id }
        val activeSessionsByTaskId = repository.listOwnerWorkSessions()
            .filter { it.status != WorkSessionStatus.CANCELLED && it.taskId != null }
            .groupBy { it.taskId!! }

        val scopedItems = repository.listAll()
            .filter { isVisibleTask(it, repository.ownerUserId, workspacesById.keys) }
            .filter { query.workspaceFilter.matches(it.workspaceId) }
            .map { task ->
                val workspace = task.workspaceId?.let(workspacesById::get)
                val sessions = activeSessionsByTaskId[task.id].orEmpty()
                val scheduleState = if (sessions.isEmpty()) {
                    TaskScheduleState.UNSCHEDULED
                } else {
                    TaskScheduleState.HAS_SESSIONS
                }
                TaskListItem(
                    task = task,
                    workspaceName = workspace?.name,
                    workspaceArchived = workspace?.status == WorkspaceStatus.ARCHIVED,
                    canEdit = canEditTask(task, repository.ownerUserId, workspace),
                    scheduleState = scheduleState,
                    activeSessionCount = sessions.size,
                    isOverdue = task.isOverdueAt(nowMillis, zone),
                )
            }

        val totalCount = scopedItems.size
        val completedCount = scopedItems.count { it.task.status == TaskStatus.COMPLETED }
        val counts = TaskListCounts(
            totalCount = totalCount,
            incompleteCount = totalCount - completedCount,
            completedCount = completedCount,
            overdueCount = scopedItems.count { it.isOverdue },
            noDeadlineCount = scopedItems.count {
                it.task.status != TaskStatus.COMPLETED && it.task.deadline == TaskDeadline.None
            },
            unscheduledCount = scopedItems.count {
                it.task.status != TaskStatus.COMPLETED && it.scheduleState == TaskScheduleState.UNSCHEDULED
            },
        )

        val keyword = query.keyword.trim()
        val filtered = scopedItems.filter { item ->
            item.task.status in query.statuses &&
                query.deadlineFilter.matches(item.task, nowMillis, zone) &&
                (
                    keyword.isEmpty() ||
                        item.task.title.contains(keyword, ignoreCase = true) ||
                        item.task.description?.contains(keyword, ignoreCase = true) == true
                    )
        }

        val sorted = when (query.sortOrder) {
            TaskSortOrder.DEFAULT_RECENT -> filtered
            TaskSortOrder.DEADLINE_ASC -> filtered.sortedWith(
                compareBy<TaskListItem> { it.task.deadline.sortKeyMillis(zone) }
                    .thenByDescending { priorityWeight(it.task.priority) }
                    .thenBy { it.task.id },
            )
            TaskSortOrder.PRIORITY_DESC -> filtered.sortedWith(
                compareByDescending<TaskListItem> { priorityWeight(it.task.priority) }
                    .thenBy { it.task.deadline.sortKeyMillis(zone) }
                    .thenBy { it.task.id },
            )
        }

        return TaskListSnapshot(query = query, items = sorted, counts = counts)
    }

    fun observe(
        queryFlow: Flow<TaskQuery>,
        clock: () -> Long = ::currentEpochMillis,
        zone: LocalZone = defaultZone,
    ): Flow<TaskListSnapshot> = combine(queryFlow, repository.revision) { query, _ ->
        invoke(query = query, nowMillis = clock(), zone = zone)
    }

    private fun priorityWeight(priority: TaskPriority): Int = when (priority) {
        TaskPriority.HIGH -> 2
        TaskPriority.NORMAL -> 1
        TaskPriority.LOW -> 0
    }
}

/** 读取单条任务详情，聚合工作区只读状态、可编辑权限与本人关联工作时段。 */
class GetTaskDetailUseCase(
    private val repository: TaskRepository,
    private val defaultZone: LocalZone = LocalZone.systemDefault(),
) {
    suspend operator fun invoke(
        taskId: String,
        nowMillis: Long = currentEpochMillis(),
        zone: LocalZone = defaultZone,
    ): TaskDetail? {
        TaskInput.requireId(taskId)
        val task = repository.findById(taskId) ?: return null
        val workspace = task.workspaceId?.let { repository.findWorkspaceById(it) }
        val accessibleWorkspaceIds = if (workspace != null) setOf(workspace.id) else emptySet()
        if (!isVisibleTask(task, repository.ownerUserId, accessibleWorkspaceIds)) return null

        val sessions = repository.listOwnerWorkSessionsByTask(task.id)
        val futurePlanned = sessions.filter {
            it.status == WorkSessionStatus.PLANNED && it.startsAt > nowMillis
        }
        return TaskDetail(
            task = task,
            workspace = workspace,
            canEdit = canEditTask(task, repository.ownerUserId, workspace),
            workSessions = sessions,
            futurePlannedSessions = futurePlanned,
            isOverdue = task.isOverdueAt(nowMillis, zone),
        )
    }
}
