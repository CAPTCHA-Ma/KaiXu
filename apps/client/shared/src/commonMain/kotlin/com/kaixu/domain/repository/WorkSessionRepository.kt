package com.kaixu.domain.repository

import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.FocusDuration
import com.kaixu.domain.model.scheduling.FocusSession
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * 个人工作时段及关联排期上下文在单次本地写事务内的受控读写范围。
 * 仅当整个事务块正常提交且产生实际写入后，仓储才递增 [WorkSessionRepository.revision]；
 * 若中途抛出异常回滚或未产生写入，修订号保持不变。
 */
interface WorkSessionWriteScope {
    val ownerUserId: String
    suspend fun findById(id: String): WorkSession?
    suspend fun listByTask(taskId: String): List<WorkSession>
    suspend fun listInRange(start: Long, end: Long): List<WorkSession>
    suspend fun findTaskById(id: String): Task?
    suspend fun findWorkspaceById(id: String): Workspace?
    suspend fun listAllFixedEvents(): List<FixedEvent>
    suspend fun listFixedEventOverridesIncludingDeleted(): List<FixedEvent>
    suspend fun listFocusSessions(workSessionId: String): List<FocusSession>
    suspend fun getFocusDuration(workSessionId: String): FocusDuration

    suspend fun insertSessions(values: List<WorkSession>): List<WorkSession>
    suspend fun replaceSession(value: WorkSession): Boolean
    suspend fun removeSessionById(id: String): Boolean
}

/** 绑定当前本地空间的本人身份；查询不暴露其他人的私人时段，通过共享修订号通知下游按需重算。 */
interface WorkSessionRepository {
    val ownerUserId: String

    /** 本地已确认排期及关联实体变更的递增修订号，初始为 0。 */
    val revision: StateFlow<Long>

    suspend fun findById(id: String): WorkSession?
    suspend fun listByTask(taskId: String): List<WorkSession>
    suspend fun listInRange(start: Long, end: Long): List<WorkSession>
    suspend fun createAll(values: List<WorkSession>): List<WorkSession>
    suspend fun update(value: WorkSession): Boolean
    suspend fun deleteById(id: String): Boolean

    /** 日程业务校验与详情聚合所需的本地上下文只读能力。 */
    suspend fun findTaskById(id: String): Task?
    suspend fun findWorkspaceById(id: String): Workspace?
    suspend fun listWorkspaces(status: WorkspaceStatus? = null): List<Workspace>
    suspend fun listFixedEventsInRange(start: Long, end: Long): List<FixedEvent>
    suspend fun listAllFixedEvents(): List<FixedEvent>
    suspend fun listFixedEventOverridesIncludingDeleted(): List<FixedEvent>
    suspend fun listFocusSessions(workSessionId: String): List<FocusSession>
    suspend fun getFocusDuration(workSessionId: String): FocusDuration

    /** 在同一本地写事务中执行跨表检查与写入，提交成功且发生修改后递增 [revision]。 */
    suspend fun <R> writeTransaction(block: suspend WorkSessionWriteScope.() -> R): R
}
