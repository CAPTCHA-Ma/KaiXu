package com.kaixu.domain.usecase.tasks

import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.repository.TaskWriteScope
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
internal fun currentEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

internal fun isVisibleTask(
    task: Task,
    ownerUserId: String,
    accessibleWorkspaceIds: Set<String>,
): Boolean =
    task.createdBy == ownerUserId ||
        task.assigneeUserId == ownerUserId ||
        (task.workspaceId != null && task.workspaceId in accessibleWorkspaceIds)

internal fun canEditTask(
    task: Task,
    ownerUserId: String,
    workspace: Workspace?,
): Boolean =
    task.createdBy == ownerUserId &&
        (
            task.workspaceId == null ||
                (workspace != null && workspace.ownerUserId == ownerUserId && workspace.status == WorkspaceStatus.ACTIVE)
            )

internal suspend fun TaskWriteScope.requireEditableTask(task: Task) {
    require(task.createdBy == ownerUserId) { "当前只能操作本人创建的本地任务" }
    val workspaceId = task.workspaceId ?: return
    val workspace = findWorkspaceById(workspaceId)
    require(workspace != null && workspace.ownerUserId == ownerUserId && workspace.status == WorkspaceStatus.ACTIVE) {
        "所属工作区已归档或不可用，任务只读"
    }
}

internal suspend fun TaskWriteScope.requireActiveTargetWorkspace(workspaceId: String?) {
    if (workspaceId == null) return
    val workspace = findWorkspaceById(workspaceId)
    require(workspace != null && workspace.ownerUserId == ownerUserId && workspace.status == WorkspaceStatus.ACTIVE) {
        "只能关联本人进行中的工作区"
    }
}
