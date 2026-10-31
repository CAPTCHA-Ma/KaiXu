package com.kaixu.domain.usecase.tasks

import com.kaixu.domain.repository.TaskRepository

/** 请求从数据源重新读取任务，失败交给调用方转为界面状态。 */
class RefreshTasksUseCase(private val repository: TaskRepository) {
    suspend operator fun invoke() = repository.refresh()
}
