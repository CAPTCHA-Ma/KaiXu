package com.kaixu.data.mapper

import com.kaixu.data.datasource.local.entity.FocusSessionEntity
import com.kaixu.data.datasource.local.entity.FocusSegmentEntity
import com.kaixu.domain.model.scheduling.FocusSession
import com.kaixu.domain.model.scheduling.FocusSegment

internal fun FocusSessionEntity.toDomain() = FocusSession(
    id, workSessionId, startedAt, ownerUserId, status, endedAt, feedback,
)

internal fun FocusSession.toEntity(original: FocusSessionEntity? = null): FocusSessionEntity =
    (original ?: FocusSessionEntity(id, workSessionId, startedAt, ownerUserId, status, endedAt, feedback)).copy(
        workSessionId = workSessionId, startedAt = startedAt, ownerUserId = ownerUserId,
        status = status, endedAt = endedAt, feedback = feedback,
    )

internal fun FocusSegmentEntity.toDomain() = FocusSegment(id, sessionId, clientSegmentId, startsAt, endsAt, mode)
internal fun FocusSegment.toEntity() = FocusSegmentEntity(id, sessionId, clientSegmentId, startsAt, endsAt, mode)
