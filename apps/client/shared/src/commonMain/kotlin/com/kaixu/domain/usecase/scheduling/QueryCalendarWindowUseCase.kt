package com.kaixu.domain.usecase.scheduling

import com.kaixu.core.platform.CalendarDate
import com.kaixu.core.platform.LocalZone
import com.kaixu.domain.model.scheduling.CalendarBlockKind
import com.kaixu.domain.model.scheduling.CalendarDayColumn
import com.kaixu.domain.model.scheduling.CalendarDayLayout
import com.kaixu.domain.model.scheduling.CalendarDeadlineItem
import com.kaixu.domain.model.scheduling.CalendarEventBlock
import com.kaixu.domain.model.scheduling.CalendarQuery
import com.kaixu.domain.model.scheduling.CalendarWindowSnapshot
import com.kaixu.domain.model.scheduling.CalendarWindowSpec
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.Recurrence
import com.kaixu.domain.model.scheduling.ScheduleOccurrence
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.model.scheduling.matches
import com.kaixu.domain.model.tasks.TaskDeadline
import com.kaixu.domain.model.tasks.TaskStatus
import com.kaixu.domain.model.tasks.deadline
import com.kaixu.domain.model.tasks.isOverdueAt
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.repository.FixedEventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
internal fun currentSchedulingEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * 面向日/周平铺日历的一体化窗口查询：
 * 聚合本人固定事件、可读共享事件与本人未取消工作时段进入时间轴切片与分列结果，
 * 并将未完成任务截止要求独立放入各日顶部截止条，支持工作区独立筛选与 revision 响应式重算。
 */
class QueryCalendarWindowUseCase(
    private val repository: FixedEventRepository,
    private val defaultZone: LocalZone = LocalZone.systemDefault(),
) {
    suspend operator fun invoke(
        query: CalendarQuery,
        nowMillis: Long = currentSchedulingEpochMillis(),
        zone: LocalZone = defaultZone,
    ): CalendarWindowSnapshot {
        val spec = CalendarWindowSpec.of(query.mode, query.anchorDate, zone)
        val ownerId = repository.ownerUserId
        val workspacesById = repository.listWorkspaces(status = null).associateBy { it.id }
        val accessibleWorkspaceIds = workspacesById.keys
        val allTasks = repository.listTasks()
        val tasksById = allTasks.associateBy { it.id }

        val allEvents = repository.listAll()
        val eventsById = allEvents.associateBy { it.id }
        val visibleEvents = allEvents.filter { isVisibleFixedEvent(it, ownerId, accessibleWorkspaceIds) }
        val expandedOccurrences = Recurrence.expand(
            events = visibleEvents,
            overridesIncludingCancelled = repository.listOverridesIncludingDeleted(),
            windowStart = spec.windowStart,
            windowEnd = spec.windowEnd,
            zone = zone,
        )

        val eventBlocks = expandedOccurrences.map { occurrence ->
            val workspace = occurrence.workspaceId?.let(workspacesById::get)
            val editable = canEditOccurrence(occurrence, ownerId, workspace, eventsById, workspacesById)
            CalendarEventBlock(
                key = occurrence.key,
                kind = if (occurrence.createdBy == ownerId) {
                    CalendarBlockKind.FIXED_EVENT
                } else {
                    CalendarBlockKind.SHARED_EVENT
                },
                recordId = occurrence.eventId,
                title = occurrence.title,
                description = occurrence.description,
                startsAt = occurrence.startsAt,
                endsAt = occurrence.endsAt,
                workspaceId = occurrence.workspaceId,
                workspaceName = workspace?.name,
                workspaceArchived = workspace?.status == WorkspaceStatus.ARCHIVED,
                isRecurring = occurrence.isRecurring,
                seriesId = occurrence.seriesId,
                originalDate = occurrence.originalDate,
                reminderOffsetMinutes = occurrence.reminderOffsetMinutes,
                canEdit = editable,
                canDelete = editable,
            )
        }

        val sessionBlocks = repository.listOwnerWorkSessionsInRange(spec.windowStart, spec.windowEnd)
            .filter {
                it.ownerUserId == ownerId &&
                    it.status != WorkSessionStatus.CANCELLED &&
                    it.startsAt < spec.windowEnd &&
                    it.endsAt > spec.windowStart
            }
            .map { session ->
                val task = session.taskId?.let(tasksById::get)
                val workspace = task?.workspaceId?.let(workspacesById::get)
                val workspaceActive = task?.workspaceId == null ||
                    (workspace != null && workspace.ownerUserId == ownerId && workspace.status == WorkspaceStatus.ACTIVE)
                val editableSession = session.status == WorkSessionStatus.PLANNED &&
                    task != null &&
                    task.status != TaskStatus.COMPLETED &&
                    workspaceActive
                CalendarEventBlock(
                    key = session.id,
                    kind = CalendarBlockKind.WORK_SESSION,
                    recordId = session.id,
                    title = task?.title ?: "我的工作时段",
                    description = session.note,
                    startsAt = session.startsAt,
                    endsAt = session.endsAt,
                    workspaceId = task?.workspaceId,
                    workspaceName = workspace?.name,
                    workspaceArchived = workspace?.status == WorkspaceStatus.ARCHIVED,
                    taskId = session.taskId,
                    workSessionStatus = session.status,
                    canEdit = editableSession,
                    canDelete = true,
                )
            }

        val blocks = (eventBlocks + sessionBlocks)
            .filter { query.workspaceFilter.matches(it.workspaceId) }
            .sortedWith(compareBy({ it.startsAt }, { it.key }))

        val deadlines = allTasks
            .asSequence()
            .filter { it.createdBy == ownerId || it.assigneeUserId == ownerId }
            .filter { it.status != TaskStatus.COMPLETED }
            .filter { query.workspaceFilter.matches(it.workspaceId) }
            .mapNotNull { task ->
                val resolvedDeadline = task.deadline
                val matched = when (resolvedDeadline) {
                    TaskDeadline.None -> null
                    is TaskDeadline.DateOnly -> {
                        if (CalendarDate.compareDates(resolvedDeadline.date, spec.startDate) >= 0 &&
                            CalendarDate.compareDates(resolvedDeadline.date, spec.endDateInclusive) <= 0
                        ) {
                            resolvedDeadline.date to zone.startOfDayMillis(resolvedDeadline.date)
                        } else {
                            null
                        }
                    }
                    is TaskDeadline.ExactTime -> {
                        if (resolvedDeadline.epochMillis >= spec.windowStart &&
                            resolvedDeadline.epochMillis < spec.windowEnd
                        ) {
                            zone.localDateOf(resolvedDeadline.epochMillis) to resolvedDeadline.epochMillis
                        } else {
                            null
                        }
                    }
                } ?: return@mapNotNull null

                val (date, sortMillis) = matched
                val workspace = task.workspaceId?.let(workspacesById::get)
                val editable = task.createdBy == ownerId &&
                    (
                        task.workspaceId == null ||
                            (workspace != null && workspace.ownerUserId == ownerId && workspace.status == WorkspaceStatus.ACTIVE)
                        )
                CalendarDeadlineItem(
                    key = task.id,
                    taskId = task.id,
                    title = task.title,
                    date = date,
                    deadline = resolvedDeadline,
                    status = task.status,
                    priority = task.priority,
                    isOverdue = task.isOverdueAt(nowMillis, zone),
                    workspaceId = task.workspaceId,
                    workspaceName = workspace?.name,
                    workspaceArchived = workspace?.status == WorkspaceStatus.ARCHIVED,
                    canEdit = editable,
                ) to sortMillis
            }
            .sortedWith(compareBy({ it.second }, { it.first.key }))
            .map { it.first }
            .toList()

        val slicesByDate = CalendarDayLayout.sliceIntoDays(blocks, spec, zone)
        val deadlinesByDate = deadlines.groupBy { it.date }
        val days = spec.dates.map { date ->
            CalendarDayColumn(
                date = date,
                dayStartMillis = zone.startOfDayMillis(date),
                dayEndMillis = zone.startOfNextDayMillis(date),
                slices = slicesByDate[date].orEmpty(),
                deadlines = deadlinesByDate[date].orEmpty(),
            )
        }

        return CalendarWindowSnapshot(
            spec = spec,
            workspaceFilter = query.workspaceFilter,
            blocks = blocks,
            deadlines = deadlines,
            days = days,
        )
    }

    fun observe(
        queryFlow: Flow<CalendarQuery>,
        clock: () -> Long = ::currentSchedulingEpochMillis,
        zone: LocalZone = defaultZone,
    ): Flow<CalendarWindowSnapshot> = combine(queryFlow, repository.revision) { query, _ ->
        invoke(query = query, nowMillis = clock(), zone = zone)
    }

    private fun canEditOccurrence(
        occurrence: ScheduleOccurrence,
        ownerId: String,
        workspace: Workspace?,
        eventsById: Map<String, FixedEvent>,
        workspacesById: Map<String, Workspace>,
    ): Boolean {
        val ownAndActive = occurrence.createdBy == ownerId &&
            (
                occurrence.workspaceId == null ||
                    (workspace != null && workspace.ownerUserId == ownerId && workspace.status == WorkspaceStatus.ACTIVE)
                )
        if (!ownAndActive) return false
        val parentSeries = occurrence.seriesId?.let(eventsById::get) ?: return true
        val parentWorkspace = parentSeries.workspaceId?.let(workspacesById::get)
        return canEditFixedEvent(parentSeries, ownerId, parentWorkspace)
    }
}
