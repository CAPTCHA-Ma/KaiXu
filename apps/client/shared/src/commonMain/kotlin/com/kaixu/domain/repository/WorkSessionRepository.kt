package com.kaixu.domain.repository

import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.workspaces.Workspace

/** 绑定当前本地空间的本人身份；查询不暴露其他人的私人时段。返回即时快照。 */
interface WorkSessionRepository {
    val ownerUserId: String
    suspend fun findById(id: String): WorkSession?
    suspend fun listByTask(taskId: String): List<WorkSession>
    suspend fun listInRange(start: Long, end: Long): List<WorkSession>
    suspend fun createAll(values: List<WorkSession>): List<WorkSession>
    suspend fun update(value: WorkSession): Boolean
    suspend fun deleteById(id: String): Boolean

    /** 日程业务校验所需的本地上下文；不代表共享项目的授权实现。 */
    suspend fun findTaskById(id: String): Task?
    suspend fun findWorkspaceById(id: String): Workspace?
    suspend fun listFixedEventsInRange(start: Long, end: Long): List<FixedEvent>
}
