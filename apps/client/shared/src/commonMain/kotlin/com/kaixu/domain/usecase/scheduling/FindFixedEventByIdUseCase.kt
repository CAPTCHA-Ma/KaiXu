package com.kaixu.domain.usecase.scheduling

import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.repository.FixedEventRepository

/** 按 ID 查询单条固定事件，不存在返回 null。 */
class FindFixedEventByIdUseCase(private val repository: FixedEventRepository) {
    suspend operator fun invoke(id: String): FixedEvent? {
        require(id.isNotBlank()) { "固定事件 ID 不能为空" }
        return repository.findById(id)
    }
}
