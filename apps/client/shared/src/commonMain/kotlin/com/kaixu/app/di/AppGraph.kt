package com.kaixu.app.di

import com.kaixu.data.repository.DefaultTaskRepository
import com.kaixu.data.datasource.TaskDataSource
import com.kaixu.domain.repository.TaskRepository
import com.kaixu.domain.usecase.tasks.TaskUseCases

/** 组合根：平台显式注入数据源，并管理数据库生命周期。 */
class AppGraph(
    source: TaskDataSource,
) {
    val taskRepository: TaskRepository = DefaultTaskRepository(source)
    val taskUseCases = TaskUseCases(taskRepository)
}

