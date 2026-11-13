package com.kaixu.domain.repository

import com.kaixu.domain.model.scheduling.FixedEvent
import kotlinx.coroutines.flow.StateFlow

/** 固定事件的业务数据能力；实现位于 data/repository，调用方只依赖本接口。 */
interface FixedEventRepository {
    /** 已确认事件的只读快照，按开始时间排序。 */
    val events: StateFlow<List<FixedEvent>>

    /** 从数据源重新读取全部未删除事件。 */
    suspend fun refresh()

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
}
