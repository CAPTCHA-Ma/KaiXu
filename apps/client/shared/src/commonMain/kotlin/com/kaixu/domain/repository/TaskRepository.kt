package com.kaixu.domain.repository

import com.kaixu.domain.model.tasks.Task
import kotlinx.coroutines.flow.StateFlow

/** 业务动作只依赖此接口，不需要知道内存、缓存或 HTTP 的细节。 */
interface TaskRepository {
    val tasks: StateFlow<List<Task>>
    suspend fun refresh()
    /** 完整业务字段；仅 Task 中的可空字段允许 null。成功后返回已保存对象。 */
    suspend fun create(task: Task): Task
    /** 按 id 完整替换业务字段，可空字段传 null 表示清空；不存在返回 false。 */
    suspend fun update(task: Task): Boolean
    suspend fun deleteById(id: String): Boolean
    suspend fun findById(id: String): Task?
    suspend fun listByWorkspace(workspaceId: String): List<Task>
    /** 标题、描述的忽略大小写子串匹配；空关键字返回全部活动任务。 */
    suspend fun search(keyword: String): List<Task>
}

