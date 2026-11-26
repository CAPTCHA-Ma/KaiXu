package com.kaixu.domain.usecase.scheduling

import com.kaixu.core.platform.LocalZone
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.Recurrence
import com.kaixu.domain.model.scheduling.ScheduleOccurrence
import com.kaixu.domain.repository.FixedEventRepository
import com.kaixu.domain.validation.scheduling.FixedEventForm
import com.kaixu.domain.validation.scheduling.SchedulingInput
import kotlinx.coroutines.flow.StateFlow

/** 固定事件与日/周平铺日程查询的统一业务入口；共用同一个固定事件仓储与本地修订信号。 */
class FixedEventUseCases(
    private val repository: FixedEventRepository,
    private val defaultZone: LocalZone = LocalZone.systemDefault(),
) {
    val getDetail = GetFixedEventDetailUseCase(repository, defaultZone)
    val create = CreateFixedEventCommandUseCase(repository, defaultZone)
    val update = UpdateFixedEventCommandUseCase(repository, defaultZone)
    val delete = DeleteFixedEventCommandUseCase(repository, defaultZone)
    val queryCalendar = QueryCalendarWindowUseCase(repository, defaultZone)

    val revision: StateFlow<Long> get() = repository.revision

    fun observe(): StateFlow<List<FixedEvent>> = repository.events

    suspend fun refresh() = repository.refresh()

    suspend fun findById(id: String): FixedEvent? {
        FixedEventForm.requireId(id)
        return repository.findById(id)
    }

    suspend fun listByWorkspace(workspaceId: String): List<FixedEvent> {
        SchedulingInput.requireId(workspaceId)
        return repository.listByWorkspace(workspaceId)
    }

    suspend fun listInRange(ownerId: String, start: Long, end: Long): List<FixedEvent> {
        SchedulingInput.requireId(ownerId)
        SchedulingInput.requireRange(start, end)
        return repository.listInRange(ownerId, start, end)
    }

    suspend fun occurrences(
        windowStart: Long,
        windowEnd: Long,
        zone: LocalZone = defaultZone,
    ): List<ScheduleOccurrence> {
        SchedulingInput.requireRange(windowStart, windowEnd)
        val ownerEvents = repository.listAll().filter { it.createdBy == repository.ownerUserId }
        val overrides = repository.listOverridesIncludingDeleted()
        return Recurrence.expand(ownerEvents, overrides, windowStart, windowEnd, zone)
    }
}
