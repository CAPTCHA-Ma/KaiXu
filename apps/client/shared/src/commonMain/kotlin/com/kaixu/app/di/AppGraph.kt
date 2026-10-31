package com.kaixu.app.di

import com.kaixu.data.repository.DefaultTaskRepository
import com.kaixu.data.datasource.memory.InMemoryTaskDataSource
import com.kaixu.domain.repository.TaskRepository
import com.kaixu.domain.usecase.tasks.CreateTaskUseCase
import com.kaixu.domain.usecase.tasks.ObserveTasksUseCase
import com.kaixu.domain.usecase.tasks.RefreshTasksUseCase

/** 组合根：替换数据源时从这里接线，View 和 ViewModel 不需要改HTTP细节。 */
class AppGraph {
    private val demoSource = InMemoryTaskDataSource()
    val taskRepository: TaskRepository = DefaultTaskRepository(demoSource)
    val createTask = CreateTaskUseCase(taskRepository)
    val observeTasks = ObserveTasksUseCase(taskRepository)
    val refreshTasks = RefreshTasksUseCase(taskRepository)

    fun simulateNextSaveFailure() = demoSource.failNextCreate()
}

