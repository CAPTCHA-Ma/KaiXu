package com.kaixu.data.datasource.local

import com.kaixu.data.datasource.FixedEventDataSource
import com.kaixu.data.datasource.local.dao.FixedEventDao
import com.kaixu.data.mapper.toDomain
import com.kaixu.data.mapper.toEntity
import com.kaixu.domain.model.scheduling.FixedEvent

class RoomFixedEventDataSource(private val dao: FixedEventDao) : FixedEventDataSource {
    override suspend fun listEvents(): List<FixedEvent> = dao.list().map { it.toDomain() }
    override suspend fun findEventById(id: String): FixedEvent? = dao.findById(id)?.toDomain()

    override suspend fun createEvent(value: FixedEvent): FixedEvent {
        val entity = value.toEntity()
        dao.insert(entity)
        return entity.toDomain()
    }

    override suspend fun updateEvent(value: FixedEvent): Boolean {
        val original = dao.findById(value.id) ?: return false
        return dao.update(value.toEntity(original)) == 1
    }

    override suspend fun deleteEventById(id: String): Boolean = dao.deleteById(id) == 1
    override suspend fun listEventsByWorkspace(workspaceId: String): List<FixedEvent> =
        dao.listByWorkspace(workspaceId).map { it.toDomain() }
    override suspend fun listEventsInRange(ownerId: String, start: Long, end: Long): List<FixedEvent> =
        dao.listInRange(ownerId, start, end).map { it.toDomain() }
}
