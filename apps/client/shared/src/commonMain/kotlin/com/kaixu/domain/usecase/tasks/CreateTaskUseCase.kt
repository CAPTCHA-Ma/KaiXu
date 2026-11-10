package com.kaixu.domain.usecase.tasks

import com.kaixu.domain.repository.TaskRepository
import com.kaixu.domain.validation.tasks.TaskInput
import com.kaixu.domain.model.tasks.Task

/** 校验完整业务字段、规范化标题，再请求仓储创建并返回已保存任务。 */
class CreateTaskUseCase(private val repository: TaskRepository) {
    suspend operator fun invoke(task: Task): Task = repository.create(TaskInput.normalized(task))
}
