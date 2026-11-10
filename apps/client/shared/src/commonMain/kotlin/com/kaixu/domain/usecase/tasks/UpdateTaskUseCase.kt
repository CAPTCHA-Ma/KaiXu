package com.kaixu.domain.usecase.tasks

import com.kaixu.domain.repository.TaskRepository
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.validation.tasks.TaskInput

class UpdateTaskUseCase(private val repository: TaskRepository) {
    suspend operator fun invoke(task: Task): Boolean {
        return repository.update(TaskInput.normalized(task))
    }
}

