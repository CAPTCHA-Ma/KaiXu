package com.kaixu.domain.usecase.scheduling

import com.kaixu.core.platform.LocalZone
import com.kaixu.data.datasource.FixedEventDataSource
import com.kaixu.data.datasource.TaskDataSource
import com.kaixu.data.datasource.WorkSessionDataSource
import com.kaixu.data.datasource.WorkspaceDataSource
import com.kaixu.domain.model.scheduling.Recurrence
import com.kaixu.domain.model.scheduling.ScheduleDeadline
import com.kaixu.domain.model.scheduling.ScheduleEntry
import com.kaixu.domain.model.scheduling.ScheduleEntryType
import com.kaixu.domain.model.scheduling.ScheduleView
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.tasks.TaskStatus
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.repository.LocalTransaction
import com.kaixu.domain.validation.scheduling.ShanghaiTime

/** 日程的工作区范围；切换时保留日期窗口与类型标识。 */
sealed interface ScheduleWorkspaceFilter {
    data object All : ScheduleWorkspaceFilter
    data object Standalone : ScheduleWorkspaceFilter
    data class Workspace(val id: String) : ScheduleWorkspaceFilter
}

/**
 * 按窗口聚合本人固定事件、本人工作时段和有权访问的共享事件，
 * 并把任务截止要求单独返回，避免截止日期进入执行时间轴。
 */
class ViewScheduleUseCase(
    private val events: FixedEventDataSource,
    private val sessions: WorkSessionDataSource,
    private val tasks: TaskDataSource,
    private val workspaces: WorkspaceDataSource,
    private val ownerUserId: String,
    private val transaction: LocalTransaction,
) {
    suspend operator fun invoke(
        windowStart: Long,
        windowEnd: Long,
        filter: ScheduleWorkspaceFilter = ScheduleWorkspaceFilter.All,
    ): ScheduleView = transaction.run {
        require(windowEnd > windowStart) { "结束时间必须晚于开始时间。" }
        val workspaceById = workspaces.listWorkspaces(null).associateBy { it.id }
        // 本地空间没有成员表；本人拥有的工作区（含已归档）即视为可访问的共享范围。
        val accessibleWorkspaceIds = workspaceById.values
            .filter { it.ownerUserId == ownerUserId }
            .mapTo(mutableSetOf()) { it.id }

        val visibleEvents = events.listEvents().filter { event ->
            event.createdBy == ownerUserId ||
                (event.workspaceId != null && event.workspaceId in accessibleWorkspaceIds)
        }
        val eventEntries = Recurrence.expand(
            visibleEvents,
            events.listOverridesIncludingDeleted(),
            windowStart,
            windowEnd,
            LocalZone.of("Asia/Shanghai"),
        ).map { occurrence ->
            val workspace = occurrence.workspaceId?.let(workspaceById::get)
            ScheduleEntry(
                key = occurrence.key,
                type = if (occurrence.createdBy == ownerUserId) {
                    ScheduleEntryType.FIXED_EVENT
                } else {
                    ScheduleEntryType.SHARED_EVENT
                },
                recordId = occurrence.eventId,
                title = occurrence.title,
                startsAt = occurrence.startsAt,
                endsAt = occurrence.endsAt,
                workspaceId = occurrence.workspaceId,
                workspaceName = workspace?.name,
                workspaceArchived = workspace.isArchived(),
                isRecurring = occurrence.isRecurring,
                seriesId = occurrence.seriesId,
                originalDate = occurrence.originalDate,
            )
        }

        val sessionEntries = sessions.listWorkSessionsInRange(ownerUserId, windowStart, windowEnd)
            .filter { it.ownerUserId == ownerUserId && it.status != WorkSessionStatus.CANCELLED }
            .map { session ->
                val task = session.taskId?.let { tasks.findTaskById(it) }
                val workspace = task?.workspaceId?.let(workspaceById::get)
                ScheduleEntry(
                    key = session.id,
                    type = ScheduleEntryType.WORK_SESSION,
                    recordId = session.id,
                    title = task?.title ?: "我的工作时段",
                    startsAt = session.startsAt,
                    endsAt = session.endsAt,
                    workspaceId = task?.workspaceId,
                    workspaceName = workspace?.name,
                    workspaceArchived = workspace.isArchived(),
                    taskId = session.taskId,
                )
            }

        val entries = (eventEntries + sessionEntries)
            .filter { filter.matches(it.workspaceId) }
            .sortedWith(compareBy({ it.startsAt }, { it.key }))

        val firstDate = ShanghaiTime.localDateOf(windowStart)
        val lastDate = ShanghaiTime.localDateOf(windowEnd - 1)
        val deadlines = tasks.listTasks()
            .filter { it.isOwnedBy(ownerUserId) && it.status != TaskStatus.COMPLETED }
            .mapNotNull { task -> task.toDeadline(firstDate, lastDate, windowStart, windowEnd, workspaceById) }
            .filter { filter.matches(it.workspaceId) }
            .sortedWith(compareBy({ it.sortMillis() }, { it.key }))

        ScheduleView(entries = entries, deadlines = deadlines)
    }

    private fun Task.toDeadline(
        firstDate: String,
        lastDate: String,
        windowStart: Long,
        windowEnd: Long,
        workspaceById: Map<String, Workspace>,
    ): ScheduleDeadline? {
        val inWindow = (deadlineDate != null && deadlineDate in firstDate..lastDate) ||
            (deadlineAt != null && deadlineAt >= windowStart && deadlineAt < windowEnd)
        if (!inWindow) return null
        val workspace = workspaceId?.let(workspaceById::get)
        return ScheduleDeadline(
            key = id,
            taskId = id,
            title = title,
            date = deadlineDate,
            at = deadlineAt,
            workspaceId = workspaceId,
            workspaceName = workspace?.name,
            workspaceArchived = workspace.isArchived(),
        )
    }

    private fun Task.isOwnedBy(ownerId: String): Boolean =
        createdBy == ownerId || assigneeUserId == ownerId

    /** 仅日期截止按当天零点参与排序，避免与精确时刻混排时落到列表末尾。 */
    private fun ScheduleDeadline.sortMillis(): Long =
        at ?: date?.let { ShanghaiTime.atLocalDate(it, 0) } ?: Long.MAX_VALUE

    private fun Workspace?.isArchived(): Boolean = this?.status == WorkspaceStatus.ARCHIVED

    private fun ScheduleWorkspaceFilter.matches(workspaceId: String?): Boolean = when (this) {
        ScheduleWorkspaceFilter.All -> true
        ScheduleWorkspaceFilter.Standalone -> workspaceId == null
        is ScheduleWorkspaceFilter.Workspace -> workspaceId == id
    }
}
