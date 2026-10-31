package com.kaixu.domain.usecase.tasks

import com.kaixu.domain.repository.TaskRepository
import com.kaixu.domain.validation.tasks.TaskTitle

/** 一个客户端业务动作：校验并规范化标题，再请求仓储创建任务。 */
class CreateTaskUseCase(private val repository: TaskRepository) {
    suspend operator fun invoke(rawTitle: String) {
        val error = TaskTitle.errorOrNull(rawTitle)
        require(error == null) { error ?: "无效标题" }
        repository.create(rawTitle.trim())
    }
}
