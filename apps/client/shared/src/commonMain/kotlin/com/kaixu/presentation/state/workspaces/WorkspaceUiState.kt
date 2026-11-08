package com.kaixu.presentation.state.workspaces

import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceKind
import com.kaixu.domain.model.workspaces.WorkspaceStatus

data class WorkspaceDraft(
    val workspaceId: String? = null,
    val kind: WorkspaceKind = WorkspaceKind.COURSE,
    val name: String = "",
    val description: String = "",
    val linkedCourseId: String? = null,
    val nameError: String? = null,
    val courseError: String? = null,
    val saveError: String? = null,
)

data class WorkspaceUiState(
    val allWorkspaces: List<Workspace> = emptyList(),
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val statusFilter: WorkspaceStatus? = WorkspaceStatus.ACTIVE,
    val kindFilter: WorkspaceKind? = null,
    val searchQuery: String = "",
    val selectedId: String? = null,
    val detailNavigationRevision: Int = 0,
    val draft: WorkspaceDraft? = null,
    val isSaving: Boolean = false,
    val actionError: String? = null,
    val archiveConfirmationId: String? = null,
) {
    val visibleWorkspaces: List<Workspace>
        get() = allWorkspaces.filter {
            (statusFilter == null || it.status == statusFilter) &&
                (kindFilter == null || it.kind == kindFilter) &&
                it.name.contains(searchQuery.trim(), ignoreCase = true)
        }

    val selectedWorkspace: Workspace?
        get() = allWorkspaces.find { it.id == selectedId }

    val activeCourses: List<Workspace>
        get() = allWorkspaces.filter { it.kind == WorkspaceKind.COURSE && it.status == WorkspaceStatus.ACTIVE }

    val linkedCourse: Workspace?
        get() = allWorkspaces.find { it.id == selectedWorkspace?.linkedCourseId }

    val selectedDraftCourse: Workspace?
        get() = allWorkspaces.find { it.id == draft?.linkedCourseId }

    val relatedProjects: List<Workspace>
        get() = allWorkspaces.filter {
            selectedId != null && it.kind == WorkspaceKind.PROJECT && it.linkedCourseId == selectedId
        }

    val activeCount: Int
        get() = allWorkspaces.count { it.status == WorkspaceStatus.ACTIVE }

    val archivedCount: Int
        get() = allWorkspaces.count { it.status == WorkspaceStatus.ARCHIVED }
}
