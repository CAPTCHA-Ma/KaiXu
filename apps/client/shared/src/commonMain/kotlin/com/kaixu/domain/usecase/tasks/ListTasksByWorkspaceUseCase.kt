package com.kaixu.domain.usecase.tasks

import com.kaixu.domain.repository.TaskRepository
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.validation.tasks.TaskInput

class ListTasksByWorkspaceUseCase(private val repository: TaskRepository) {
    suspend operator fun invoke(workspaceId: String): List<Task> {
        TaskInput.requireId(workspaceId)
        return repository.listByWorkspace(workspaceId)
    }
}

