package com.kaixu.domain.usecase.scheduling

import com.kaixu.domain.model.scheduling.FocusDuration
import com.kaixu.domain.model.scheduling.FocusSessionStatus
import com.kaixu.domain.model.scheduling.TaskWorkSessionItem
import com.kaixu.domain.model.scheduling.TaskWorkSessionsSnapshot
import com.kaixu.domain.model.scheduling.WorkSessionDetail
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.model.scheduling.isDetached
import com.kaixu.domain.model.scheduling.toEditDraft
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.repository.WorkSessionRepository
import com.kaixu.domain.validation.scheduling.SchedulingInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * 读取单条个人工作时段详情及能力快照，支持基于共享 `revision` 的响应式订阅。
 * 脱链时段仍可正常查看详情、读取历史专注时长并执行删除，但禁止编辑、改期与开始计时。
 */
class GetWorkSessionDetailUseCase(
    private val repository: WorkSessionRepository,
) {
    suspend operator fun invoke(sessionId: String): WorkSessionDetail? {
        if (sessionId.isBlank()) return null
        val session = repository.findById(sessionId) ?: return null
        val task = session.taskId?.let { repository.findTaskById(it) }
        val workspace = task?.workspaceId?.let { repository.findWorkspaceById(it) }
        val focusSessions = repository.listFocusSessions(session.id)
        val focusDuration = repository.getFocusDuration(session.id)

        val detached = session.isDetached
        val editable = isEditableWorkSession(session, repository.ownerUserId, task, workspace)
        val canTransition = canTransitionWorkSessionStatus(session, repository.ownerUserId, task, workspace)
        val hasOpenFocus = focusSessions.any {
            it.status == FocusSessionStatus.ACTIVE || it.status == FocusSessionStatus.PAUSED
        }

        return WorkSessionDetail(
            session = session,
            task = task,
            workspace = workspace,
            workspaceArchived = workspace?.status == WorkspaceStatus.ARCHIVED,
            isDetached = detached,
            focusSessions = focusSessions,
            focusDuration = focusDuration,
            canEdit = editable,
            canReschedule = editable,
            canEditFocusTarget = editable && focusSessions.isEmpty(),
            canComplete = canTransition,
            canCancel = canTransition,
            canDelete = session.ownerUserId == repository.ownerUserId,
            canStartFocus = editable && !hasOpenFocus,
            initialDraft = if (editable) session.toEditDraft() else null,
        )
    }

    fun observe(sessionId: String): Flow<WorkSessionDetail?> =
        repository.revision.map { invoke(sessionId) }

    fun observe(sessionIdFlow: Flow<String>): Flow<WorkSessionDetail?> =
        combine(sessionIdFlow, repository.revision) { id, _ -> invoke(id) }
}

/**
 * 按任务聚合本人的全部个人工作时段明细、各状态数量、未取消计划总时长与实际累计专注时长。
 * 支持基于共享 `revision` 的响应式观察，任务状态变化、新增排期或专注计时提交后自动重算。
 */
class QueryTaskWorkSessionsUseCase(
    private val repository: WorkSessionRepository,
) {
    suspend operator fun invoke(taskId: String): TaskWorkSessionsSnapshot {
        SchedulingInput.requireId(taskId)
        val task = repository.findTaskById(taskId)
        val workspace = task?.workspaceId?.let { repository.findWorkspaceById(it) }
        val workspaceArchived = workspace?.status == WorkspaceStatus.ARCHIVED
        val canScheduleMore = isPlannableTask(task, repository.ownerUserId, workspace)

        if (task == null) {
            return TaskWorkSessionsSnapshot(
                taskId = taskId,
                task = null,
                workspace = null,
                workspaceArchived = false,
                canScheduleMore = false,
            )
        }

        val sessions = repository.listByTask(task.id)
        val items = sessions.map { session ->
            val focusSessions = repository.listFocusSessions(session.id)
            val focusDuration = repository.getFocusDuration(session.id)
            val editable = isEditableWorkSession(session, repository.ownerUserId, task, workspace)
            val canTransition = canTransitionWorkSessionStatus(session, repository.ownerUserId, task, workspace)
            val hasOpenFocus = focusSessions.any {
                it.status == FocusSessionStatus.ACTIVE || it.status == FocusSessionStatus.PAUSED
            }
            TaskWorkSessionItem(
                session = session,
                focusSessions = focusSessions,
                focusDuration = focusDuration,
                canEdit = editable,
                canReschedule = editable,
                canEditFocusTarget = editable && focusSessions.isEmpty(),
                canComplete = canTransition,
                canCancel = canTransition,
                canDelete = session.ownerUserId == repository.ownerUserId,
                canStartFocus = editable && !hasOpenFocus,
            )
        }

        val plannedCount = items.count { it.session.status == WorkSessionStatus.PLANNED }
        val completedCount = items.count { it.session.status == WorkSessionStatus.COMPLETED }
        val cancelledCount = items.count { it.session.status == WorkSessionStatus.CANCELLED }
        val totalPlannedMillis = items
            .filter { it.session.status != WorkSessionStatus.CANCELLED }
            .sumOf { it.plannedDurationMillis }
        val totalFocusDuration = FocusDuration(
            foregroundMillis = items.sumOf { it.focusDuration.foregroundMillis },
            backgroundMillis = items.sumOf { it.focusDuration.backgroundMillis },
        )

        return TaskWorkSessionsSnapshot(
            taskId = task.id,
            task = task,
            workspace = workspace,
            workspaceArchived = workspaceArchived,
            canScheduleMore = canScheduleMore,
            items = items,
            plannedCount = plannedCount,
            completedCount = completedCount,
            cancelledCount = cancelledCount,
            totalPlannedMillis = totalPlannedMillis,
            totalFocusDuration = totalFocusDuration,
        )
    }

    fun observe(taskId: String): Flow<TaskWorkSessionsSnapshot> =
        repository.revision.map { invoke(taskId) }

    fun observe(taskIdFlow: Flow<String>): Flow<TaskWorkSessionsSnapshot> =
        combine(taskIdFlow, repository.revision) { id, _ -> invoke(id) }
}
