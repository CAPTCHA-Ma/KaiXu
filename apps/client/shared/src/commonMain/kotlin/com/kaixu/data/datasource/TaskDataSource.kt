package com.kaixu.data.datasource

import com.kaixu.domain.model.tasks.Task

/** 任务持久化访问边界。查询排除软删除；更新/删除返回是否命中活动记录。 */
interface TaskDataSource {
    suspend fun listTasks(): List<Task>
    suspend fun createTask(title: String): Task
    suspend fun findTaskById(id: String): Task?
    suspend fun createTask(value: Task): Task
    suspend fun updateTask(value: Task): Boolean
    suspend fun deleteTaskById(id: String): Boolean
    suspend fun listTasksByWorkspace(workspaceId: String): List<Task>
}

