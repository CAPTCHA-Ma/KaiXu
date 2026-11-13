package com.kaixu.testing

import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.repository.FixedEventRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 固定事件仓储的测试替身：记录调用次数，可注入失败，不做真实持久化。 */
class TestFixedEventRepository(initial: List<FixedEvent> = emptyList()) : FixedEventRepository {
    private val _events = MutableStateFlow(initial)
    override val events = _events.asStateFlow()

    var createCalls = 0
    var updateCalls = 0
    var deleteCalls = 0
    var refreshCalls = 0
    var failNextWrite = false
    var failNextRefresh = false
    var refreshResult: List<FixedEvent>? = null
    var lastSaved: FixedEvent? = null

    override suspend fun refresh() {
        refreshCalls++
        if (failNextRefresh) {
            failNextRefresh = false
            error("测试读取失败")
        }
        refreshResult?.let { _events.value = it }
    }

    override suspend fun create(fixedEvent: FixedEvent): FixedEvent {
        createCalls++
        failIfRequested()
        val saved = if (fixedEvent.id.isBlank()) fixedEvent.copy(id = "generated-$createCalls") else fixedEvent
        lastSaved = saved
        _events.value = (_events.value + saved).sortedEvents()
        return saved
    }

    override suspend fun update(fixedEvent: FixedEvent): Boolean {
        updateCalls++
        failIfRequested()
        if (_events.value.none { it.id == fixedEvent.id }) return false
        lastSaved = fixedEvent
        _events.value = _events.value
            .map { if (it.id == fixedEvent.id) fixedEvent else it }
            .sortedEvents()
        return true
    }

    override suspend fun deleteById(id: String): Boolean {
        deleteCalls++
        failIfRequested()
        val exists = _events.value.any { it.id == id }
        if (exists) _events.value = _events.value.filterNot { it.id == id }
        return exists
    }

    override suspend fun findById(id: String): FixedEvent? =
        _events.value.firstOrNull { it.id == id }

    override suspend fun listByWorkspace(workspaceId: String): List<FixedEvent> =
        _events.value.filter { it.workspaceId == workspaceId }

    override suspend fun listInRange(ownerId: String, start: Long, end: Long): List<FixedEvent> =
        _events.value.filter { it.createdBy == ownerId && it.startsAt < end && it.endsAt > start }

    private fun failIfRequested() {
        if (failNextWrite) {
            failNextWrite = false
            error("测试写入失败")
        }
    }

    private fun List<FixedEvent>.sortedEvents(): List<FixedEvent> =
        sortedWith(compareBy({ it.startsAt }, { it.id }))
}
