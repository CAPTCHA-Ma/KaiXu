package com.kaixu.data.mapper

import com.kaixu.data.datasource.local.entity.TaskEntity
import com.kaixu.domain.model.tasks.Task

internal fun TaskEntity.toDomain(): Task = Task(
    id = id,
    title = title,
    description = description,
    workspaceId = workspaceId,
    createdBy = createdBy,
    assigneeUserId = assigneeUserId,
    status = status,
    priority = priority,
    deadlineDate = deadlineDate,
    deadlineAt = deadlineAt,
    estimatedMinutes = estimatedMinutes,
    blockedNote = blockedNote,
    completedAt = completedAt,
    sourceExcerpt = sourceExcerpt,
    sourceUrl = sourceUrl,
)

/** 修改时从已存实体复制，保留创建时间与同步元数据。 */
internal fun Task.toEntity(original: TaskEntity? = null): TaskEntity =
    (original ?: TaskEntity(
        id = id,
        title = title,
        description = description,
        workspaceId = workspaceId,
        createdBy = createdBy,
        assigneeUserId = assigneeUserId,
        status = status,
        priority = priority,
        deadlineDate = deadlineDate,
        deadlineAt = deadlineAt,
        estimatedMinutes = estimatedMinutes,
        blockedNote = blockedNote,
        completedAt = completedAt,
        sourceExcerpt = sourceExcerpt,
        sourceUrl = sourceUrl,
    )).copy(
        id = id,
        title = title,
        description = description,
        workspaceId = workspaceId,
        createdBy = createdBy,
        assigneeUserId = assigneeUserId,
        status = status,
        priority = priority,
        deadlineDate = deadlineDate,
        deadlineAt = deadlineAt,
        estimatedMinutes = estimatedMinutes,
        blockedNote = blockedNote,
        completedAt = completedAt,
        sourceExcerpt = sourceExcerpt,
        sourceUrl = sourceUrl,
    )
