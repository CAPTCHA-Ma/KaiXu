package com.kaixu.data.datasource

import com.kaixu.domain.model.scheduling.FixedEvent

/** 本地 CRUD；更新/删除返回是否命中活动记录，失败抛出异常，查询排除软删除。 */
interface FixedEventDataSource {
    suspend fun listEvents(): List<FixedEvent>
    suspend fun findEventById(id: String): FixedEvent?
    suspend fun createEvent(value: FixedEvent): FixedEvent
    suspend fun updateEvent(value: FixedEvent): Boolean
    suspend fun deleteEventById(id: String): Boolean
    suspend fun listEventsByWorkspace(workspaceId: String): List<FixedEvent>
    suspend fun listEventsInRange(ownerId: String, start: Long, end: Long): List<FixedEvent>

    /** 展开重复序列所需的例外行，包含已取消（软删除）的记录。 */
    suspend fun listOverridesIncludingDeleted(): List<FixedEvent>

    /** 截断重复序列的结束日期；until 为空表示不再重复。 */
    suspend fun truncateSeriesUntil(id: String, until: String?): Boolean
}
