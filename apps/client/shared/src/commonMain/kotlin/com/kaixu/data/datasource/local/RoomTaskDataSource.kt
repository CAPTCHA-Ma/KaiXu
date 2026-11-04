package com.kaixu.data.datasource.local

import com.kaixu.data.datasource.TaskDataSource
import com.kaixu.data.datasource.local.dao.TaskDao
import com.kaixu.data.mapper.toDomain
import com.kaixu.data.mapper.toEntity
import com.kaixu.domain.model.tasks.Task
import com.kaixu.data.datasource.local.entity.TaskEntity

class RoomTaskDataSource(private val dao: TaskDao) : TaskDataSource {
    override suspend fun listTasks(): List<Task> = dao.list().map { it.toDomain() }
    override suspend fun findTaskById(id: String): Task? = dao.findById(id)?.toDomain()

    override suspend fun createTask(title: String): Task {
        val entity = TaskEntity(title = title)
        dao.insert(entity)
        return entity.toDomain()
    }

    override suspend fun createTask(value: Task): Task {
        val entity = value.toEntity()
        dao.insert(entity)
        return entity.toDomain()
    }

    override suspend fun updateTask(value: Task): Boolean {
        val original = dao.findById(value.id) ?: return false
        return dao.update(value.toEntity(original)) == 1
    }

    override suspend fun deleteTaskById(id: String): Boolean = dao.deleteById(id) == 1
    override suspend fun listTasksByWorkspace(workspaceId: String): List<Task> =
        dao.listByWorkspace(workspaceId).map { it.toDomain() }
}
