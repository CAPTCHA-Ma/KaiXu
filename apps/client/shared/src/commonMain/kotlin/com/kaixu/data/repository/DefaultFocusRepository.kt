package com.kaixu.data.repository

import com.kaixu.data.datasource.FocusDataSource
import com.kaixu.domain.model.scheduling.*
import com.kaixu.domain.repository.FocusRepository
import com.kaixu.domain.repository.SchedulingTransaction
import com.kaixu.domain.validation.scheduling.SchedulingInput

class DefaultFocusRepository(
    private val source: FocusDataSource,
    override val ownerUserId: String,
    private val transaction: SchedulingTransaction,
) : FocusRepository {
    init { SchedulingInput.requireId(ownerUserId) }

    override suspend fun findSessionById(id: String): FocusSession? {
        SchedulingInput.requireId(id)
        return source.findFocusSessionById(id)?.takeIf { it.ownerUserId == ownerUserId }
    }

    override suspend fun createSession(value: FocusSession): FocusSession {
        val checked = SchedulingInput.focusSession(value)
        require(checked.ownerUserId == ownerUserId) { "只能保存本人计时会话" }
        return source.createFocusSession(checked)
    }

    override suspend fun updateSession(value: FocusSession): Boolean = transaction.run {
        val checked = SchedulingInput.focusSession(value)
        require(checked.ownerUserId == ownerUserId) { "只能修改本人计时会话" }
        if (findSessionById(checked.id) == null) false else source.updateFocusSession(checked)
    }

    override suspend fun listSessions(workSessionId: String): List<FocusSession> {
        SchedulingInput.requireId(workSessionId)
        return source.listFocusSessions(ownerUserId, workSessionId)
            .filter { it.ownerUserId == ownerUserId && it.workSessionId == workSessionId }
            .sortedWith(compareBy(FocusSession::startedAt, FocusSession::id))
    }

    override suspend fun appendSegment(value: FocusSegment): FocusSegment = transaction.run {
        SchedulingInput.segment(value)
        requireNotNull(findSessionById(value.sessionId)) { "计时会话不存在或不属于本人" }
        source.appendFocusSegment(value)
    }

    override suspend fun listSegments(sessionId: String): List<FocusSegment> {
        if (findSessionById(sessionId) == null) return emptyList()
        return source.listFocusSegments(sessionId).filter { it.sessionId == sessionId }
            .sortedWith(compareBy(FocusSegment::startsAt, FocusSegment::id))
    }

    override suspend fun getDuration(workSessionId: String): FocusDuration {
        SchedulingInput.requireId(workSessionId)
        // source 以 owner + 计划时段查询，软删除计划后仍允许读取本人计时历史。
        return source.getFocusDuration(ownerUserId, workSessionId)
    }
}
