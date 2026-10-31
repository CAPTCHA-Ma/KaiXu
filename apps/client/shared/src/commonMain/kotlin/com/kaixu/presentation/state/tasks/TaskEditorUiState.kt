package com.kaixu.presentation.state.tasks

import com.kaixu.domain.model.tasks.Task

/** ViewModel 对 View 的只读输出；不持久化为任务字段。 */
data class TaskEditorUiState(
    val draftTitle: String = "",
    val tasks: List<Task> = emptyList(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val savedMessage: String? = null,
)

