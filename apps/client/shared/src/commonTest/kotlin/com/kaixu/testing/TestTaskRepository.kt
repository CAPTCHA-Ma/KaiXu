package com.kaixu.testing

import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.repository.TaskRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 仅用于领域与 ViewModel 单元测试；控制延迟/失败，不进入正式客户端。 */
class TestTaskRepository(private val latencyMillis: Long = 0) : TaskRepository {
    private val records = MutableStateFlow<List<Task>>(emptyList())
    override val tasks = records.asStateFlow()
    private var failNext = false

    fun failNextCreate() { failNext = true }
    override suspend fun refresh() = Unit

    override suspend fun create(task: Task): Task {
        delay(latencyMillis)
        if (failNext) {
            failNext = false
            error("测试保存失败")
        }
        records.value = listOf(task) + records.value
        return task
    }

    override suspend fun update(task: Task): Boolean {
        if (records.value.none { it.id == task.id }) return false
        records.value = records.value.map { if (it.id == task.id) task else it }
        return true
    }
    override suspend fun deleteById(id: String): Boolean {
        val found = records.value.any { it.id == id }
        records.value = records.value.filterNot { it.id == id }
        return found
    }
    override suspend fun findById(id: String): Task? = records.value.find { it.id == id }
    override suspend fun listByWorkspace(workspaceId: String): List<Task> =
        records.value.filter { it.workspaceId == workspaceId }
    override suspend fun search(keyword: String): List<Task> =
        records.value.filter { it.title.contains(keyword, true) || it.description?.contains(keyword, true) == true }
}
