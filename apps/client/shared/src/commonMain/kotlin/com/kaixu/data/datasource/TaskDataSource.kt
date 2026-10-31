package com.kaixu.data.datasource

import com.kaixu.domain.model.tasks.Task

/** Model 数据源边界：未来 HTTP 实现也遵守同一个接口。 */
interface TaskDataSource {
    suspend fun listTasks(): List<Task>
    suspend fun createTask(title: String): Task
}

