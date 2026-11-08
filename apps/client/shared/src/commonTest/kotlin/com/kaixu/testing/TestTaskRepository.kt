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
    private var failRefresh = false

    fun failNextCreate() { failNext = true }
    fun failNextRefresh() { failRefresh = true }

    override suspend fun refresh() {
        if (failRefresh) {
            failRefresh = false
            error("测试读取失败")
        }
    }

    override suspend fun create(title: String) {
        delay(latencyMillis)
        if (failNext) {
            failNext = false
            error("测试保存失败")
        }
        records.value = listOf(Task(id = "test-${records.value.size + 1}", title = title)) + records.value
    }
}
