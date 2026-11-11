package com.kaixu.domain.usecase.tasks

import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.repository.TaskRepository
import com.kaixu.domain.validation.tasks.TaskInput
import kotlinx.coroutines.flow.StateFlow

/** 任务业务动作的统一入口；共用一个仓储，不维护额外列表或页面状态。 */
class TaskUseCases(private val repository: TaskRepository) {
    /** 校验完整业务字段并规范化标题，返回已经保存的任务。 */
    suspend fun create(task: Task): Task = repository.create(TaskInput.normalized(task))

    /** 按 ID 替换业务字段；不存在时返回 false。 */
    suspend fun update(task: Task): Boolean = repository.update(TaskInput.normalized(task))

    suspend fun deleteById(id: String): Boolean {
        TaskInput.requireId(id)
        return repository.deleteById(id)
    }

    suspend fun findById(id: String): Task? {
        TaskInput.requireId(id)
        return repository.findById(id)
    }

    suspend fun listByWorkspace(workspaceId: String): List<Task> {
        TaskInput.requireId(workspaceId)
        return repository.listByWorkspace(workspaceId)
    }

    suspend fun search(keyword: String): List<Task> = repository.search(keyword.trim())

    /** 返回仓储确认的数据流；不额外维护另一份任务列表。 */
    fun observe(): StateFlow<List<Task>> = repository.tasks

    /** 重读持久化数据，失败或取消原样交给调用方处理。 */
    suspend fun refresh() = repository.refresh()
}
