package com.kaixu.data.repository

import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.repository.TaskRepository
import com.kaixu.data.datasource.TaskDataSource
import com.kaixu.domain.validation.tasks.TaskInput
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
    override suspend fun create(task: Task): Task = mutex.withLock {
        val saved = source.createTask(TaskInput.normalized(task))
        _tasks.value = listOf(saved) + _tasks.value.filterNot { it.id == saved.id }
        saved
    }

    override suspend fun update(task: Task): Boolean = mutex.withLock {
        val value = TaskInput.normalized(task)
        val updated = source.updateTask(value)
        if (updated) {
            _tasks.value = if (_tasks.value.any { it.id == value.id }) {
                _tasks.value.map { if (it.id == value.id) value else it }
            } else listOf(value) + _tasks.value
        }
        updated
    }

    override suspend fun deleteById(id: String): Boolean = mutex.withLock {
        TaskInput.requireId(id)
        val deleted = source.deleteTaskById(id)
        if (deleted) _tasks.value = _tasks.value.filterNot { it.id == id }
        deleted
    }

    override suspend fun findById(id: String): Task? = mutex.withLock {
        TaskInput.requireId(id)
        source.findTaskById(id)
    }

    override suspend fun listByWorkspace(workspaceId: String): List<Task> = mutex.withLock {
        TaskInput.requireId(workspaceId)
        source.listTasksByWorkspace(workspaceId)
    }

    override suspend fun search(keyword: String): List<Task> = mutex.withLock {
        val query = keyword.trim()
        source.listTasks().filter {
            it.title.contains(query, ignoreCase = true) || it.description?.contains(query, ignoreCase = true) == true
        }
    }
}

