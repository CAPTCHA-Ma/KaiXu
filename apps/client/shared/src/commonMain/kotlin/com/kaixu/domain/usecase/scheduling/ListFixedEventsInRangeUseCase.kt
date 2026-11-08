package com.kaixu.domain.usecase.scheduling

import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.repository.FixedEventRepository

/** 按所有者与时间窗口查询固定事件；窗口为半开区间，相邻首尾不算重叠。 */
class ListFixedEventsInRangeUseCase(private val repository: FixedEventRepository) {
    suspend operator fun invoke(ownerId: String, start: Long, end: Long): List<FixedEvent> {
        require(ownerId.isNotBlank()) { "所有者 ID 不能为空" }
        require(end > start) { "结束时间必须晚于开始时间" }
        return repository.listInRange(ownerId, start, end)
    }
}
