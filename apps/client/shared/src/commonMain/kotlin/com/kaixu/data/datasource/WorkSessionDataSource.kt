package com.kaixu.data.datasource

import com.kaixu.domain.model.scheduling.WorkSession

/** 本地 CRUD；更新/删除返回是否命中活动记录，失败抛出异常，查询排除软删除。 */
interface WorkSessionDataSource {
    suspend fun listWorkSessions(): List<WorkSession>
    suspend fun findWorkSessionById(id: String): WorkSession?
    suspend fun createWorkSession(value: WorkSession): WorkSession
    suspend fun updateWorkSession(value: WorkSession): Boolean
    suspend fun deleteWorkSessionById(id: String): Boolean
    suspend fun listWorkSessionsByTask(taskId: String): List<WorkSession>
    suspend fun listWorkSessionsInRange(ownerId: String, start: Long, end: Long): List<WorkSession>
}
