package com.kaixu.presentation.viewmodel.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaixu.domain.usecase.tasks.CreateTaskUseCase
import com.kaixu.domain.usecase.tasks.ObserveTasksUseCase
import com.kaixu.domain.usecase.tasks.RefreshTasksUseCase
import com.kaixu.domain.validation.tasks.TaskTitle
import com.kaixu.domain.model.tasks.Task
import com.kaixu.presentation.state.tasks.TaskEditorUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** ViewModel：接收事件、调用业务动作、发布状态；不处理存储与网络细节。 */
class TaskEditorViewModel(
    private val createTask: CreateTaskUseCase,
    private val observeTasks: ObserveTasksUseCase,
    private val refreshTasks: RefreshTasksUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TaskEditorUiState())
    val uiState = _uiState.asStateFlow()
    private var saveJob: Job? = null
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            observeTasks().collect { tasks -> _uiState.update { it.copy(tasks = tasks) } }
        }
        refresh()
    }

    /** 页面重新进入时重读原记录，保留尚未提交的输入。 */
    fun refresh() {
        if (refreshJob?.isActive == true || _uiState.value.isSaving) return
        _uiState.update { it.copy(isLoading = true) }
        refreshJob = viewModelScope.launch {
            try {
                refreshTasks()
                _uiState.update {
                    if (it.errorMessage == "读取失败，请重新打开页面") it.copy(errorMessage = null) else it
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(errorMessage = "读取失败，请重新打开页面") }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun onTitleChanged(value: String) {
        if (_uiState.value.isSaving) return
        _uiState.update {
            it.copy(draftTitle = value, errorMessage = null, savedMessage = null)
        }
    }

    fun onSaveClicked() {
        val state = _uiState.value
        if (state.isSaving || state.isLoading) return
        val error = TaskTitle.errorOrNull(state.draftTitle)
        if (error != null) {
            _uiState.update { it.copy(errorMessage = error, savedMessage = null) }
            return
        }
        // 在启动协程前置位，连续点击也不会产生第二次提交。
        _uiState.update { it.copy(isSaving = true, errorMessage = null, savedMessage = null) }
        val titleToSave = state.draftTitle.trim()
        val job = viewModelScope.launch {
            try {
                createTask(Task(title = titleToSave))
                _uiState.update {
                    it.copy(draftTitle = "", savedMessage = "已创建：$titleToSave")
                }
            } catch (cancelled: CancellationException) {
                // 取消是协程控制流程，不能显示成保存错误。
                throw cancelled
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(errorMessage = "保存失败，输入已保留。请重试。")
                }
            }
        }
        saveJob = job
        // 即使在协程首次调度前就取消，也能退出“保存中”。
        job.invokeOnCompletion {
            if (saveJob === job) _uiState.update { it.copy(isSaving = false) }
        }
    }

    /** 取消等待，不保证撤回已经提交的数据库写入。 */
    fun onCancelSaveClicked() { saveJob?.cancel() }
}

