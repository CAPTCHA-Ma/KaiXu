package com.kaixu.domain.usecase.scheduling

import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.repository.FixedEventRepository
import com.kaixu.domain.validation.scheduling.FixedEventForm

/** 更新固定事件：ID 必须存在，规则与创建一致，成功返回是否命中活动记录。 */
class UpdateFixedEventUseCase(private val repository: FixedEventRepository) {
    suspend operator fun invoke(fixedEvent: FixedEvent): Boolean {
        require(fixedEvent.id.isNotBlank()) { "更新固定事件时 ID 不能为空" }
        val error = FixedEventForm.errorOrNull(fixedEvent)
        require(error == null) { error ?: "无效固定事件" }
        return repository.update(
            fixedEvent.copy(
                title = fixedEvent.title.trim(),
                description = fixedEvent.description?.trim()?.ifBlank { null },
            ),
        )
    }
}
