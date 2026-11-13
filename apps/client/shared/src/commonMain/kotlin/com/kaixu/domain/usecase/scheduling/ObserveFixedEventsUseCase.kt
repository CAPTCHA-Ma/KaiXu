package com.kaixu.domain.usecase.scheduling

import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.repository.FixedEventRepository
import kotlinx.coroutines.flow.StateFlow

/** 订阅仓储已确认的事件快照；不另存一份列表。 */
class ObserveFixedEventsUseCase(private val repository: FixedEventRepository) {
    operator fun invoke(): StateFlow<List<FixedEvent>> = repository.events
}
