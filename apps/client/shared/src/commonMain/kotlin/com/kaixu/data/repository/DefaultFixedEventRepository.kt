package com.kaixu.data.repository

import com.kaixu.data.datasource.FixedEventDataSource
import com.kaixu.data.datasource.local.entity.newLocalId
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.repository.FixedEventRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 协调本地数据源并发布已确认事件的快照；写入成功后才更新快照。 */
class DefaultFixedEventRepository(
    private val source: FixedEventDataSource,
) : FixedEventRepository {
    private val _events = MutableStateFlow<List<FixedEvent>>(emptyList())
    override val events = _events.asStateFlow()
    private val mutex = Mutex()

    override suspend fun refresh() = mutex.withLock {
        _events.value = source.listEvents()
    }

    override suspend fun create(fixedEvent: FixedEvent): FixedEvent = mutex.withLock {
        val toSave = if (fixedEvent.id.isBlank()) fixedEvent.copy(id = newLocalId()) else fixedEvent
        val saved = source.createEvent(toSave) // 失败会抛异常，快照不变
        _events.value = (_events.value.filterNot { it.id == saved.id } + saved).sortedEvents()
        saved
    }

    override suspend fun update(fixedEvent: FixedEvent): Boolean = mutex.withLock {
        if (fixedEvent.id.isBlank()) return@withLock false
        val updated = source.updateEvent(fixedEvent)
        if (updated) {
            // 重新读取，确保快照与数据库中的最终记录一致。
            val saved = source.findEventById(fixedEvent.id) ?: fixedEvent
            _events.value = (_events.value.filterNot { it.id == saved.id } + saved).sortedEvents()
        }
        updated
    }

    override suspend fun deleteById(id: String): Boolean = mutex.withLock {
        val deleted = source.deleteEventById(id)
        if (deleted) {
            _events.value = _events.value.filterNot { it.id == id }
        }
        deleted
    }

    override suspend fun findById(id: String): FixedEvent? = source.findEventById(id)

    override suspend fun listByWorkspace(workspaceId: String): List<FixedEvent> =
        source.listEventsByWorkspace(workspaceId)

    override suspend fun listInRange(ownerId: String, start: Long, end: Long): List<FixedEvent> =
        source.listEventsInRange(ownerId, start, end)

    private fun List<FixedEvent>.sortedEvents(): List<FixedEvent> =
        sortedWith(compareBy({ it.startsAt }, { it.id }))
}
