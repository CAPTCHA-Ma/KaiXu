package com.kaixu.data.datasource

import com.kaixu.domain.model.scheduling.*

/** 实际计时记录的本地存取契约；不负责驱动手机计时器、在线心跳或同步。 */
interface FocusDataSource {
    suspend fun createFocusSession(value: FocusSession): FocusSession
    suspend fun findFocusSessionById(id: String): FocusSession?
    suspend fun updateFocusSession(value: FocusSession): Boolean
    suspend fun listFocusSessions(ownerId: String, workSessionId: String): List<FocusSession>
    /** 仅追加已确认的闭合片段；同会话、同 clientSegmentId 的相同内容重试幂等。 */
    suspend fun appendFocusSegment(value: FocusSegment): FocusSegment
    suspend fun listFocusSegments(sessionId: String): List<FocusSegment>
    suspend fun getFocusDuration(ownerId: String, workSessionId: String): FocusDuration
}
