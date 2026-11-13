package com.kaixu.domain.usecase.scheduling

import com.kaixu.domain.repository.FixedEventRepository

/** 删除固定事件；返回 false 表示记录不存在或已删除，调用方不应视为错误。 */
class DeleteFixedEventUseCase(private val repository: FixedEventRepository) {
    suspend operator fun invoke(id: String): Boolean {
        require(id.isNotBlank()) { "固定事件 ID 不能为空" }
        return repository.deleteById(id)
    }
}
