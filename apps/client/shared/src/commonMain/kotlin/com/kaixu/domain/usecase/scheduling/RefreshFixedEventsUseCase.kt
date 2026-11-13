package com.kaixu.domain.usecase.scheduling

import com.kaixu.domain.repository.FixedEventRepository

/** 请求从数据源重新读取固定事件；异常交给调用方转为界面状态。 */
class RefreshFixedEventsUseCase(private val repository: FixedEventRepository) {
    suspend operator fun invoke() = repository.refresh()
}
