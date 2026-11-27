package com.kaixu.domain.usecase.scheduling

import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.model.scheduling.isDetached
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.tasks.TaskStatus
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.repository.FocusWriteScope
import com.kaixu.domain.repository.WorkSessionRepository
import com.kaixu.domain.repository.WorkSessionWriteScope

/** 任务对本人是否可见可访问（独立任务须本人创建或指派本人；归属工作区须本人拥有）。 */
internal fun isAccessibleTaskForOwner(
    task: Task,
    ownerUserId: String,
    workspace: Workspace?,
): Boolean {
    val workspaceId = task.workspaceId
    return if (workspaceId == null) {
        task.createdBy == ownerUserId || task.assigneeUserId == ownerUserId
    } else {
        workspace != null && workspace.ownerUserId == ownerUserId
    }
}

/** 任务是否允许新增或调整个人执行时段：存在、本人可访问、未完成，且关联工作区（若有）处于进行中。 */
internal fun isPlannableTask(
    task: Task?,
    ownerUserId: String,
    workspace: Workspace?,
): Boolean {
    if (task == null || task.status == TaskStatus.COMPLETED) return false
    if (!isAccessibleTaskForOwner(task, ownerUserId, workspace)) return false
    val workspaceId = task.workspaceId ?: return true
    return workspace != null && workspace.id == workspaceId &&
        workspace.ownerUserId == ownerUserId &&
        workspace.status == WorkspaceStatus.ACTIVE
}

/** 个人工作时段是否允许编辑或拖拽改期：本人拥有、未脱链、处于 PLANNED 状态且关联任务仍可排期。 */
internal fun isEditableWorkSession(
    session: WorkSession,
    ownerUserId: String,
    task: Task?,
    workspace: Workspace?,
): Boolean =
    session.ownerUserId == ownerUserId &&
        !session.isDetached &&
        session.status == WorkSessionStatus.PLANNED &&
        isPlannableTask(task, ownerUserId, workspace)

/**
 * 个人工作时段是否允许流转为 COMPLETED / CANCELLED：
 * 本人拥有、未脱链、处于 PLANNED 状态且所属工作区（若有）未归档。
 * 注：通过 KEEP_FUTURE 保留的已完成任务时段仍允许用户将个人安排标记完成或取消。
 */
internal fun canTransitionWorkSessionStatus(
    session: WorkSession,
    ownerUserId: String,
    task: Task?,
    workspace: Workspace?,
): Boolean {
    if (session.ownerUserId != ownerUserId || session.isDetached) return false
    if (session.status != WorkSessionStatus.PLANNED) return false
    if (task == null) return false
    val workspaceId = task.workspaceId ?: return true
    return workspace != null && workspace.id == workspaceId &&
        workspace.ownerUserId == ownerUserId &&
        workspace.status == WorkspaceStatus.ACTIVE
}

internal suspend fun requirePlannableTaskContext(
    ownerUserId: String,
    taskId: String,
    findTask: suspend (String) -> Task?,
    findWorkspace: suspend (String) -> Workspace?,
): Pair<Task, Workspace?> {
    val task = requireNotNull(findTask(taskId)) { "任务不存在或不可访问" }
    require(task.status != TaskStatus.COMPLETED) { "已完成任务不能新增或调整执行安排" }
    val workspace = task.workspaceId?.let { id ->
        requireNotNull(findWorkspace(id)) { "所属工作区不存在或不可访问" }.also {
            require(it.ownerUserId == ownerUserId && it.status == WorkspaceStatus.ACTIVE) {
                "所属工作区已归档或不可编辑"
            }
        }
    }
    require(workspace != null || task.createdBy == ownerUserId || task.assigneeUserId == ownerUserId) {
        "只能安排本人可访问的任务"
    }
    return task to workspace
}

internal suspend fun WorkSessionRepository.requirePlannableTask(taskId: String): Pair<Task, Workspace?> =
    requirePlannableTaskContext(ownerUserId, taskId, ::findTaskById, ::findWorkspaceById)

internal suspend fun WorkSessionWriteScope.requirePlannableTask(taskId: String): Pair<Task, Workspace?> =
    requirePlannableTaskContext(ownerUserId, taskId, ::findTaskById, ::findWorkspaceById)

internal suspend fun FocusWriteScope.requirePlannableTask(
    taskId: String,
    fallbackPlans: WorkSessionRepository? = null,
): Pair<Task, Workspace?> = requirePlannableTaskContext(
    ownerUserId = ownerUserId,
    taskId = taskId,
    findTask = { id -> findTaskById(id) ?: fallbackPlans?.findTaskById(id) },
    findWorkspace = { id -> findWorkspaceById(id) ?: fallbackPlans?.findWorkspaceById(id) },
)

internal suspend fun WorkSessionWriteScope.requireEditableSession(session: WorkSession): Pair<Task, Workspace?> {
    require(session.ownerUserId == ownerUserId) { "计划时段不存在或不属于本人" }
    val linkedTaskId = requireNotNull(session.taskId?.takeIf { !session.isDetached }) {
        "脱链时段已只读，不可编辑或改期"
    }
    require(session.status == WorkSessionStatus.PLANNED) { "只能修改计划中的时段" }
    return requirePlannableTask(linkedTaskId)
}

internal suspend fun WorkSessionWriteScope.requireTransitionableSession(session: WorkSession) {
    require(session.ownerUserId == ownerUserId) { "计划时段不存在或不属于本人" }
    val linkedTaskId = requireNotNull(session.taskId?.takeIf { !session.isDetached }) {
        "脱链时段已只读，不可变更状态，仅支持查看或删除"
    }
    val task = requireNotNull(findTaskById(linkedTaskId)) { "关联任务不存在或不可访问" }
    val workspaceId = task.workspaceId ?: return
    val workspace = requireNotNull(findWorkspaceById(workspaceId)) { "所属工作区不存在或不可访问" }
    require(workspace.ownerUserId == ownerUserId && workspace.status == WorkspaceStatus.ACTIVE) {
        "所属工作区已归档或不可编辑"
    }
}
