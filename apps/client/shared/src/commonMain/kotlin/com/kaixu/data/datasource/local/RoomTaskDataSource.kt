package com.kaixu.data.datasource.local

import com.kaixu.data.datasource.TaskDataSource
import com.kaixu.data.datasource.local.dao.TaskDao
import com.kaixu.data.datasource.local.entity.TaskEntity
import com.kaixu.data.mapper.toDomain
import com.kaixu.domain.model.tasks.Task

class RoomTaskDataSource(private val dao: TaskDao) : TaskDataSource {
    override suspend fun listTasks(): List<Task> = dao.list().map { it.toDomain() }

    override suspend fun createTask(title: String): Task {
        val entity = TaskEntity(title = title)
        dao.insert(entity)
        return entity.toDomain()
    }
}
