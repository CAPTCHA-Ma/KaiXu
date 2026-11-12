package com.kaixu.data.datasource.local

import com.kaixu.data.datasource.WorkSessionDataSource
import com.kaixu.data.datasource.local.dao.WorkSessionDao
import com.kaixu.data.mapper.toDomain
import com.kaixu.data.mapper.toEntity
import com.kaixu.domain.model.scheduling.WorkSession

class RoomWorkSessionDataSource(private val dao: WorkSessionDao) : WorkSessionDataSource {
    override suspend fun listWorkSessions(): List<WorkSession> = dao.list().map { it.toDomain() }
    override suspend fun findWorkSessionById(id: String): WorkSession? = dao.findById(id)?.toDomain()

    override suspend fun createWorkSession(value: WorkSession): WorkSession {
        val entity = value.toEntity()
        dao.insert(entity)
        return entity.toDomain()
    }

    override suspend fun updateWorkSession(value: WorkSession): Boolean {
        val original = dao.findById(value.id) ?: return false
        return dao.update(value.toEntity(original)) == 1
    }

    override suspend fun createWorkSessions(values: List<WorkSession>): List<WorkSession> {
        val entities = values.map { it.toEntity() }
        dao.insertAll(entities)
        return entities.map { it.toDomain() }
    }

    override suspend fun deleteWorkSessionById(id: String): Boolean = dao.deleteById(id) == 1
    override suspend fun listWorkSessionsByTask(taskId: String): List<WorkSession> =
        dao.listByTask(taskId).map { it.toDomain() }
    override suspend fun listWorkSessionsByOwnerAndTask(ownerId: String, taskId: String): List<WorkSession> =
        dao.listByOwnerAndTask(ownerId, taskId).map { it.toDomain() }
    override suspend fun listWorkSessionsInRange(ownerId: String, start: Long, end: Long): List<WorkSession> =
        dao.listInRange(ownerId, start, end).map { it.toDomain() }
}
