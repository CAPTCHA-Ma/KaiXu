package com.kaixu.domain.repository

import com.kaixu.domain.model.scheduling.FocusDuration
import com.kaixu.domain.model.scheduling.FocusSegment
import com.kaixu.domain.model.scheduling.FocusSession
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.workspaces.Workspace
import kotlinx.coroutines.flow.StateFlow

/**
 * 专注计时与关联个人时段在单次本地写事务内的受控读写范围。
 * 支持启动计时时在同一数据库事务内原子补写默认 `WorkSession.focusTargetSeconds` 与创建 `FocusSession`；
 * 仅当事务成功提交且发生实际修改后递增 [FocusRepository.revision]。
 */
interface FocusWriteScope {
    val ownerUserId: String
    suspend fun findSessionById(id: String): FocusSession?
    suspend fun listSessions(workSessionId: String): List<FocusSession>
    suspend fun listSegments(sessionId: String): List<FocusSegment>
    suspend fun getDuration(workSessionId: String): FocusDuration

    suspend fun findWorkSessionById(id: String): WorkSession?
    suspend fun findTaskById(id: String): Task?
    suspend fun findWorkspaceById(id: String): Workspace?
    suspend fun updateWorkSession(session: WorkSession): Boolean

    suspend fun insertSession(value: FocusSession): FocusSession
    suspend fun replaceSession(value: FocusSession): Boolean
    suspend fun insertSegment(value: FocusSegment): FocusSegment
}

/** 会话和有效片段是同一计时聚合；片段不单独提供修改、删除入口。 */
interface FocusRepository {
    val ownerUserId: String

    /** 本地计时及关联排期变更的递增修订号，初始为 0。 */
    val revision: StateFlow<Long>

    suspend fun findSessionById(id: String): FocusSession?
    suspend fun createSession(value: FocusSession): FocusSession
    suspend fun updateSession(value: FocusSession): Boolean
    suspend fun listSessions(workSessionId: String): List<FocusSession>
    suspend fun appendSegment(value: FocusSegment): FocusSegment
    suspend fun listSegments(sessionId: String): List<FocusSegment>
    suspend fun getDuration(workSessionId: String): FocusDuration

    /** 在同一本地写事务中执行计时检查与写入，提交成功且发生修改后递增 [revision]。 */
    suspend fun <R> writeTransaction(block: suspend FocusWriteScope.() -> R): R
}
