package com.kaixu.data.datasource.local

import com.kaixu.data.datasource.FocusDataSource
import com.kaixu.data.datasource.local.dao.FocusSessionDao
import com.kaixu.data.datasource.local.dao.FocusSegmentDao
import com.kaixu.data.mapper.toDomain
import com.kaixu.data.mapper.toEntity
import com.kaixu.domain.model.scheduling.*

class RoomFocusDataSource(
    private val sessions: FocusSessionDao,
    private val segments: FocusSegmentDao,
) : FocusDataSource {
    override suspend fun createFocusSession(value: FocusSession): FocusSession {
        val entity = value.toEntity()
        sessions.insert(entity)
        return entity.toDomain()
    }

    override suspend fun findFocusSessionById(id: String): FocusSession? = sessions.findById(id)?.toDomain()

    override suspend fun updateFocusSession(value: FocusSession): Boolean {
        val old = sessions.findById(value.id) ?: return false
        return sessions.update(value.toEntity(old)) == 1
    }

    override suspend fun listFocusSessions(ownerId: String, workSessionId: String): List<FocusSession> =
        sessions.listByWorkSession(ownerId, workSessionId).map { it.toDomain() }

    override suspend fun appendFocusSegment(value: FocusSegment): FocusSegment = segments.append(value.toEntity()).toDomain()

    override suspend fun listFocusSegments(sessionId: String): List<FocusSegment> =
        segments.listBySession(sessionId).map { it.toDomain() }

    override suspend fun getFocusDuration(ownerId: String, workSessionId: String): FocusDuration {
        val all = segments.listByWorkSession(ownerId, workSessionId)
        return FocusDuration(
            foregroundMillis = all.filter { it.mode == FocusMode.FOREGROUND }.sumOf { it.endsAt - it.startsAt },
            backgroundMillis = all.filter { it.mode == FocusMode.BACKGROUND }.sumOf { it.endsAt - it.startsAt },
        )
    }
}
