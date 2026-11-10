package com.kaixu.domain.usecase.tasks

import com.kaixu.domain.repository.TaskRepository
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.validation.tasks.TaskInput

class FindTaskByIdUseCase(private val repository: TaskRepository) {
    suspend operator fun invoke(id: String): Task? {
        TaskInput.requireId(id)
        return repository.findById(id)
    }
}

