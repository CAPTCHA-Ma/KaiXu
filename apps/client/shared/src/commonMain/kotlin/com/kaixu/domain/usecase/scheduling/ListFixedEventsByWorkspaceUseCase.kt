package com.kaixu.domain.usecase.scheduling

import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.repository.FixedEventRepository

/** 按工作区查询固定事件；结果只用于展示，不覆盖 events 全量快照。 */
class ListFixedEventsByWorkspaceUseCase(private val repository: FixedEventRepository) {
    suspend operator fun invoke(workspaceId: String): List<FixedEvent> {
        require(workspaceId.isNotBlank()) { "工作区 ID 不能为空" }
        return repository.listByWorkspace(workspaceId)
    }
}
