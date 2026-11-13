package com.kaixu.data.mapper

import com.kaixu.data.datasource.local.entity.FixedEventEntity
import com.kaixu.domain.model.scheduling.FixedEvent

internal fun FixedEventEntity.toDomain(): FixedEvent = FixedEvent(
    id = id,
    title = title,
    startsAt = startsAt,
    endsAt = endsAt,
    description = description,
    workspaceId = workspaceId,
    createdBy = createdBy,
    reminderOffsetMinutes = reminderOffsetMinutes,
    repeatWeekdays = repeatWeekdays?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet(),
    repeatUntil = repeatUntil,
    seriesId = seriesId,
    originalDate = originalDate,
    isOverride = isOverride,
    isCancelled = deletedAt != null,
)

/** 修改时从已存实体复制，保留创建时间与同步元数据。 */
internal fun FixedEvent.toEntity(original: FixedEventEntity? = null): FixedEventEntity =
    (original ?: FixedEventEntity(
        id = id,
        title = title,
        startsAt = startsAt,
        endsAt = endsAt,
        description = description,
        workspaceId = workspaceId,
        createdBy = createdBy,
        reminderOffsetMinutes = reminderOffsetMinutes,
        repeatWeekdays = repeatWeekdays?.toStorage(),
        repeatUntil = repeatUntil,
        seriesId = seriesId,
        originalDate = originalDate,
        isOverride = isOverride,
    )).copy(
        id = id,
        title = title,
        startsAt = startsAt,
        endsAt = endsAt,
        description = description,
        workspaceId = workspaceId,
        createdBy = createdBy,
        reminderOffsetMinutes = reminderOffsetMinutes,
        repeatWeekdays = repeatWeekdays?.toStorage(),
        repeatUntil = repeatUntil,
        seriesId = seriesId,
        originalDate = originalDate,
        isOverride = isOverride,
    )

private fun Set<Int>.toStorage(): String = sorted().joinToString(",")
