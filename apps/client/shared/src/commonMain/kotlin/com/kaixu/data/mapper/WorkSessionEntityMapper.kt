package com.kaixu.data.mapper

import com.kaixu.data.datasource.local.entity.WorkSessionEntity
import com.kaixu.domain.model.scheduling.WorkSession

internal fun WorkSessionEntity.toDomain(): WorkSession = WorkSession(
    id = id,
    taskId = taskId,
    startsAt = startsAt,
    endsAt = endsAt,
    ownerUserId = ownerUserId,
    stageId = stageId,
    note = note,
    status = status,
    isLocked = isLocked,
    overlapConfirmed = overlapConfirmed,
    detachedAt = detachedAt,
    detachReason = detachReason,
    focusTargetSeconds = focusTargetSeconds,
)

/** 修改时从已存实体复制，保留创建时间与同步元数据。 */
internal fun WorkSession.toEntity(original: WorkSessionEntity? = null): WorkSessionEntity =
    (original ?: WorkSessionEntity(
        id = id,
        taskId = taskId,
        startsAt = startsAt,
        endsAt = endsAt,
        ownerUserId = ownerUserId,
        stageId = stageId,
        note = note,
        status = status,
        isLocked = isLocked,
        overlapConfirmed = overlapConfirmed,
        detachedAt = detachedAt,
        detachReason = detachReason,
        focusTargetSeconds = focusTargetSeconds,
    )).copy(
        id = id,
        taskId = taskId,
        startsAt = startsAt,
        endsAt = endsAt,
        ownerUserId = ownerUserId,
        stageId = stageId,
        note = note,
        status = status,
        isLocked = isLocked,
        overlapConfirmed = overlapConfirmed,
        detachedAt = detachedAt,
        detachReason = detachReason,
        focusTargetSeconds = focusTargetSeconds,
    )
