package com.kaixu.presentation.state.workspaces

import com.kaixu.domain.model.tasks.TaskStatus
import com.kaixu.domain.usecase.workspaces.WorkspaceDeadlineFilter
import com.kaixu.domain.usecase.workspaces.WorkspaceOverview

data class WorkspaceOverviewUiState(
    val workspaceId: String? = null,
    val overview: WorkspaceOverview? = null,
    val isLoading: Boolean = false,
    val loadError: String? = null,
    val statusFilter: TaskStatus? = null,
    val deadlineFilter: WorkspaceDeadlineFilter = WorkspaceDeadlineFilter.ALL,
)
