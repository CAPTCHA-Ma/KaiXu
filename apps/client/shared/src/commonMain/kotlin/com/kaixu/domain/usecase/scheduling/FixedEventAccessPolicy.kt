package com.kaixu.domain.usecase.scheduling

import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.repository.FixedEventWriteScope

internal fun isVisibleFixedEvent(
    event: FixedEvent,
    ownerUserId: String,
    accessibleWorkspaceIds: Set<String>,
): Boolean =
    event.createdBy == ownerUserId ||
        (event.workspaceId != null && event.workspaceId in accessibleWorkspaceIds)

internal fun canEditFixedEvent(
    event: FixedEvent,
    ownerUserId: String,
    workspace: Workspace?,
): Boolean =
    event.createdBy == ownerUserId &&
        (
            event.workspaceId == null ||
                (workspace != null && workspace.ownerUserId == ownerUserId && workspace.status == WorkspaceStatus.ACTIVE)
            )

internal suspend fun FixedEventWriteScope.requireEditableEvent(event: FixedEvent) {
    require(event.createdBy == ownerUserId) { "只能操作本人创建的固定事件" }
    val workspaceId = event.workspaceId ?: return
    val workspace = findWorkspaceById(workspaceId)
    require(workspace != null && workspace.ownerUserId == ownerUserId && workspace.status == WorkspaceStatus.ACTIVE) {
        "所属工作区已归档或不可用，事件只读"
    }
}

internal suspend fun FixedEventWriteScope.requireActiveTargetWorkspace(workspaceId: String?) {
    if (workspaceId == null) return
    val workspace = findWorkspaceById(workspaceId)
    require(workspace != null && workspace.ownerUserId == ownerUserId && workspace.status == WorkspaceStatus.ACTIVE) {
        "只能关联本人进行中的工作区"
    }
}
