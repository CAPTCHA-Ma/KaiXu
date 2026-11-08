package com.kaixu.domain.usecase.scheduling

import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.repository.FixedEventRepository
import com.kaixu.domain.validation.scheduling.FixedEventForm

/** 创建固定事件：复用表单规则，规范化文本后交给仓储。 */
class CreateFixedEventUseCase(private val repository: FixedEventRepository) {
    suspend operator fun invoke(fixedEvent: FixedEvent): FixedEvent {
        val error = FixedEventForm.errorOrNull(fixedEvent)
        require(error == null) { error ?: "无效固定事件" }
        return repository.create(
            fixedEvent.copy(
                title = fixedEvent.title.trim(),
                description = fixedEvent.description?.trim()?.ifBlank { null },
            ),
        )
    }
}
