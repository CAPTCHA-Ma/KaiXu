package com.kaixu.data.datasource.memory

import com.kaixu.domain.model.tasks.Task
import com.kaixu.data.datasource.TaskDataSource
import com.kaixu.data.datasource.local.entity.newLocalId
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 仅用于示例：延迟期间可取消；失败在真正写入前发生。 */
class InMemoryTaskDataSource(private val latencyMillis: Long = 600) : TaskDataSource {
    private val mutex = Mutex()
    private val records = mutableListOf<Task>()
    private var failNext = false

    fun failNextCreate() { failNext = true }

    override suspend fun listTasks(): List<Task> = mutex.withLock { records.toList() }

    override suspend fun createTask(title: String): Task {
        delay(latencyMillis)
        return mutex.withLock {
            if (failNext) {
                failNext = false
                throw DemoSaveException()
            }
            Task(newLocalId(), title).also { records.add(0, it) }
        }
    }
}

class DemoSaveException : Exception("模拟保存失败")

