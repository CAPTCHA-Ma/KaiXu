package com.kaixu.domain.usecase.tasks

import com.kaixu.domain.repository.TaskRepository
import com.kaixu.domain.validation.tasks.TaskInput

class DeleteTaskUseCase(private val repository: TaskRepository) {
    suspend operator fun invoke(id: String): Boolean {
        TaskInput.requireId(id)
        return repository.deleteById(id)
    }
}

