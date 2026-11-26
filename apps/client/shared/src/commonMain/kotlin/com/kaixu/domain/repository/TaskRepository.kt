package com.kaixu.domain.repository

import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * 任务及其关联排期在单次原子写事务内的受控读写操作。
 * 仅当整个事务块正常提交返回后，仓储才同步更新内存快照并递增 revision；
 * 若中途抛出异常或未执行任何写入，快照与 revision 均保持原状。
 */
interface TaskWriteScope {
    val ownerUserId: String
    suspend fun findTaskById(id: String): Task?
    suspend fun findWorkspaceById(id: String): Workspace?
    suspend fun listOwnerWorkSessionsByTask(taskId: String): List<WorkSession>
    suspend fun insertTask(task: Task): Task
    suspend fun replaceTask(task: Task): Boolean
    suspend fun removeTaskById(id: String): Boolean
    suspend fun updateOwnerWorkSession(session: WorkSession): Boolean
}

/** 业务动作只依赖此接口，不需要知道内存、本地数据库或 HTTP 的细节。 */
interface TaskRepository {
    val ownerUserId: String
    val tasks: StateFlow<List<Task>>
    /** 本地已确认任务及相关排期变更的递增修订号，初始为 0。 */
    val revision: StateFlow<Long>

    suspend fun refresh()
    /** 读取全部未删除的持久化任务，不替换或裁剪 tasks 快照。 */
    suspend fun listAll(): List<Task>
    /** 完整业务字段；仅 Task 中的可空字段允许 null。成功后返回已保存对象。 */
    suspend fun create(task: Task): Task
    /** 按 id 完整替换业务字段，可空字段传 null 表示清空；不存在返回 false。 */
    suspend fun update(task: Task): Boolean
    suspend fun deleteById(id: String): Boolean
    suspend fun findById(id: String): Task?
    suspend fun listByWorkspace(workspaceId: String): List<Task>
    /** 标题、描述的忽略大小写子串匹配；空关键字返回全部活动任务。 */
    suspend fun search(keyword: String): List<Task>

    /** 任务查询与写前校验所需的本地工作区及本人工作时段读取能力。 */
    suspend fun findWorkspaceById(id: String): Workspace?
    suspend fun listWorkspaces(status: WorkspaceStatus? = null): List<Workspace>
    suspend fun listOwnerWorkSessions(): List<WorkSession>
    suspend fun listOwnerWorkSessionsByTask(taskId: String): List<WorkSession>

    /** 在同一本地写事务中执行跨记录校验与写入，提交成功后统一更新快照并递增 revision。 */
    suspend fun <R> writeTransaction(block: suspend TaskWriteScope.() -> R): R
}

