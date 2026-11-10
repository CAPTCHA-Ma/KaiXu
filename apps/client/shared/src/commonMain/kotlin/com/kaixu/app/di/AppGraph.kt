package com.kaixu.app.di

import com.kaixu.data.repository.DefaultTaskRepository
import com.kaixu.data.datasource.TaskDataSource
import com.kaixu.domain.repository.TaskRepository
import com.kaixu.domain.usecase.tasks.CreateTaskUseCase
import com.kaixu.domain.usecase.tasks.ObserveTasksUseCase
import com.kaixu.domain.usecase.tasks.RefreshTasksUseCase
import com.kaixu.domain.usecase.tasks.UpdateTaskUseCase
import com.kaixu.domain.usecase.tasks.DeleteTaskUseCase
import com.kaixu.domain.usecase.tasks.FindTaskByIdUseCase
import com.kaixu.domain.usecase.tasks.ListTasksByWorkspaceUseCase
import com.kaixu.domain.usecase.tasks.SearchTasksUseCase

/** 组合根：平台显式注入数据源，并管理数据库生命周期。 */
class AppGraph(
    source: TaskDataSource,
) {
    val taskRepository: TaskRepository = DefaultTaskRepository(source)
    val createTask = CreateTaskUseCase(taskRepository)
    val observeTasks = ObserveTasksUseCase(taskRepository)
    val refreshTasks = RefreshTasksUseCase(taskRepository)
    val updateTask = UpdateTaskUseCase(taskRepository)
    val deleteTask = DeleteTaskUseCase(taskRepository)
    val findTaskById = FindTaskByIdUseCase(taskRepository)
    val listTasksByWorkspace = ListTasksByWorkspaceUseCase(taskRepository)
    val searchTasks = SearchTasksUseCase(taskRepository)

}

