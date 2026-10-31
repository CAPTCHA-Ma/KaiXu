package com.kaixu.data.repository

import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.repository.TaskRepository
import com.kaixu.data.datasource.TaskDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Model 数据协调：成功提交后才发布新列表，失败不伪装成保存成功。 */
class DefaultTaskRepository(private val source: TaskDataSource) : TaskRepository {
    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    override val tasks = _tasks.asStateFlow()
    private val mutex = Mutex()

    override suspend fun refresh() = mutex.withLock {
        _tasks.value = source.listTasks()
    }

    override suspend fun create(title: String) = mutex.withLock {
        val saved = source.createTask(title)
        _tasks.value = listOf(saved) + _tasks.value.filterNot { it.id == saved.id }
    }
}

