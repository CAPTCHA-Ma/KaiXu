package com.kaixu.domain.repository

import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * 固定事件及关联排期上下文在单次本地写事务内的受控读写范围。
 * 仅当整个事务块正常提交返回后，仓储才统一更新内存快照并递增 revision；
 * 若中途抛出异常或未执行任何写入，快照与 revision 均保持原状。
 */
interface FixedEventWriteScope {
    val ownerUserId: String
    suspend fun listAllEvents(): List<FixedEvent>
    suspend fun listOverridesIncludingDeleted(): List<FixedEvent>
    suspend fun findEventById(id: String): FixedEvent?
    suspend fun findWorkspaceById(id: String): Workspace?
    suspend fun listOwnerWorkSessionsInRange(start: Long, end: Long): List<WorkSession>
    suspend fun findTaskById(id: String): Task?
    suspend fun insertEvent(event: FixedEvent): FixedEvent
    suspend fun replaceEvent(event: FixedEvent): Boolean
    suspend fun removeEventById(id: String): Boolean
    suspend fun truncateSeriesUntil(id: String, until: String): Boolean
}

/** 固定事件的业务数据能力；实现位于 data/repository，调用方只依赖本接口。 */
interface FixedEventRepository {
    val ownerUserId: String

    /** 已确认事件的只读快照，按开始时间与 ID 排序。 */
    val events: StateFlow<List<FixedEvent>>

    /** 本地已确认事件及关联排期变更的递增修订号，初始为 0。 */
    val revision: StateFlow<Long>

    /** 从数据源重新读取全部未删除事件，仅当快照变化时递增 revision。 */
    suspend fun refresh()

    /** 读取全部未删除的持久化固定事件，不替换或裁剪 events 快照。 */
    suspend fun listAll(): List<FixedEvent>

    /** 读取展开重复序列所需的例外行（含已取消软删除记录）。 */
    suspend fun listOverridesIncludingDeleted(): List<FixedEvent>

    /** 新建事件；调用方可传空 ID，由实现生成。失败抛出异常。 */
    suspend fun create(fixedEvent: FixedEvent): FixedEvent

    /** 更新同 ID 事件；命中活动记录返回 true。规则校验由 UseCase 负责。 */
    suspend fun update(fixedEvent: FixedEvent): Boolean

    /** 软删除事件；命中活动记录返回 true，重复删除返回 false。 */
    suspend fun deleteById(id: String): Boolean

    /** 查询单条未删除事件，不存在返回 null。 */
    suspend fun findById(id: String): FixedEvent?

    /** 按工作区查询未删除事件；不改变 events 快照。 */
    suspend fun listByWorkspace(workspaceId: String): List<FixedEvent>

    /** 按所有者与半开区间查询；不改变 events 快照。 */
    suspend fun listInRange(ownerId: String, start: Long, end: Long): List<FixedEvent>

    /** 查询本人拥有的指定工作区，不存在或属于他人返回 null。 */
    suspend fun findWorkspaceById(id: String): Workspace?

    /** 查询本人拥有的工作区列表（含归档供只读标识判定）。 */
    suspend fun listWorkspaces(status: WorkspaceStatus? = null): List<Workspace>

    /** 查询半开区间内本人的个人工作时段，按开始时间与 ID 排序。 */
    suspend fun listOwnerWorkSessionsInRange(start: Long, end: Long): List<WorkSession>

    /** 读取本地可见任务列表与单条任务（供日历截止条展示及冲突关联任务标题读取）。 */
    suspend fun listTasks(): List<Task>
    suspend fun findTaskById(id: String): Task?

    /** 在同一本地写事务中执行跨表检查与写入，提交成功后统一更新快照并递增 revision。 */
    suspend fun <R> writeTransaction(block: suspend FixedEventWriteScope.() -> R): R
}
