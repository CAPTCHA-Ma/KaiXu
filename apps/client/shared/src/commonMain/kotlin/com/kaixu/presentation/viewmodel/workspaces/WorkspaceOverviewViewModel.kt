package com.kaixu.presentation.viewmodel.workspaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaixu.domain.model.tasks.TaskStatus
import com.kaixu.domain.usecase.workspaces.WorkspaceDeadlineFilter
import com.kaixu.domain.usecase.workspaces.WorkspaceDeadlineWindow
import com.kaixu.domain.usecase.workspaces.WorkspaceOverviewUseCase
import com.kaixu.presentation.state.workspaces.WorkspaceOverviewUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WorkspaceOverviewViewModel(
    private val useCase: WorkspaceOverviewUseCase,
    private val ownerUserId: String,
    private val windowProvider: () -> WorkspaceDeadlineWindow,
) : ViewModel() {
    private val _uiState = MutableStateFlow(WorkspaceOverviewUiState())
    val uiState = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private var loadGeneration = 0L

    /** 同 ID 重读时保留筛选，换工作区重置筛选；传 null 关闭当前概览并使旧查询失效。 */
    fun refresh(workspaceId: String? = _uiState.value.workspaceId) {
        val generation = ++loadGeneration
        loadJob?.cancel()
        if (workspaceId == null) {
            _uiState.value = WorkspaceOverviewUiState()
            return
        }
        _uiState.update {
            if (workspaceId != it.workspaceId) WorkspaceOverviewUiState(workspaceId = workspaceId, isLoading = true)
            else it.copy(isLoading = true, loadError = null)
        }
        val requestedState = _uiState.value
        loadJob = viewModelScope.launch {
            try {
                val result = useCase(
                    workspaceId = workspaceId,
                    ownerUserId = ownerUserId,
                    window = windowProvider(),
                    status = requestedState.statusFilter,
                    deadline = requestedState.deadlineFilter,
                )
                if (generation == loadGeneration) _uiState.update { it.copy(overview = result) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == loadGeneration) _uiState.update { it.copy(loadError = "读取概览失败，请重试。") }
            } finally {
                if (generation == loadGeneration) _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun onStatusFilterChanged(value: TaskStatus?) {
        if (_uiState.value.statusFilter == value) return
        _uiState.update { it.copy(statusFilter = value, overview = null, loadError = null) }
        refresh()
    }

    fun onDeadlineFilterChanged(value: WorkspaceDeadlineFilter) {
        if (_uiState.value.deadlineFilter == value) return
        _uiState.update { it.copy(deadlineFilter = value, overview = null, loadError = null) }
        refresh()
    }
}
