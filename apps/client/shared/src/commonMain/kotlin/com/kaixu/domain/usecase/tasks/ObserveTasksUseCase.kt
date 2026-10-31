package com.kaixu.domain.usecase.tasks

import com.kaixu.domain.repository.TaskRepository

/** 订阅仓储确认的数据；不持有页面状态，也不自己维护另一份列表。 */
class ObserveTasksUseCase(private val repository: TaskRepository) {
    operator fun invoke() = repository.tasks
}
