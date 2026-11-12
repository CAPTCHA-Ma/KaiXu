package com.kaixu.domain.repository

import com.kaixu.domain.model.scheduling.*

/** 会话和有效片段是同一计时聚合；片段不单独提供修改、删除入口。 */
interface FocusRepository {
    val ownerUserId: String
    suspend fun findSessionById(id: String): FocusSession?
    suspend fun createSession(value: FocusSession): FocusSession
    suspend fun updateSession(value: FocusSession): Boolean
    suspend fun listSessions(workSessionId: String): List<FocusSession>
    suspend fun appendSegment(value: FocusSegment): FocusSegment
    suspend fun listSegments(sessionId: String): List<FocusSegment>
    suspend fun getDuration(workSessionId: String): FocusDuration
}
