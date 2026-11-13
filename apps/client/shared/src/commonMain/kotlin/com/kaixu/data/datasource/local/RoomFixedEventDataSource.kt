package com.kaixu.data.datasource.local

import com.kaixu.data.datasource.FixedEventDataSource
import com.kaixu.data.datasource.local.dao.FixedEventDao
import com.kaixu.data.mapper.toDomain
import com.kaixu.data.mapper.toEntity
import com.kaixu.domain.model.scheduling.FixedEvent

class RoomFixedEventDataSource(private val dao: FixedEventDao) : FixedEventDataSource {
    override suspend fun listEvents(): List<FixedEvent> = dao.list().map { it.toDomain() }
    override suspend fun findEventById(id: String): FixedEvent? = dao.findById(id)?.toDomain()

    //创建
    override suspend fun createEvent(value: FixedEvent): FixedEvent {
        val entity = value.toEntity()
        dao.insert(entity)
        return entity.toDomain()
    }

    //更新
    override suspend fun updateEvent(value: FixedEvent): Boolean {
        val original = dao.findById(value.id) ?: return false
        return dao.update(value.toEntity(original)) == 1
    }

    //通过id删除固定事件
    override suspend fun deleteEventById(id: String): Boolean = dao.deleteById(id) == 1

    //通过工作空间列出固定事件
    override suspend fun listEventsByWorkspace(workspaceId: String): List<FixedEvent> =
        dao.listByWorkspace(workspaceId).map { it.toDomain() }

    //按顺序列出固定事件
    override suspend fun listEventsInRange(ownerId: String, start: Long, end: Long): List<FixedEvent> =
        dao.listInRange(ownerId, start, end).map { it.toDomain() }

    override suspend fun listOverridesIncludingDeleted(): List<FixedEvent> =
        dao.listOverridesIncludingDeleted().map { it.toDomain() }

    override suspend fun truncateSeriesUntil(id: String, until: String?): Boolean =
        dao.truncateSeries(id, until) == 1
}
