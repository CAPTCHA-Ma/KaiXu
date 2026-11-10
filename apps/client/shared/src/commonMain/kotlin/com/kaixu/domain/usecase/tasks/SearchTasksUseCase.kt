package com.kaixu.domain.usecase.tasks

import com.kaixu.domain.repository.TaskRepository
import com.kaixu.domain.model.tasks.Task

class SearchTasksUseCase(private val repository: TaskRepository) {
    suspend operator fun invoke(keyword: String): List<Task> {
        return repository.search(keyword.trim())
    }
}

