package com.kaixu.domain.model.scheduling

/** 供仓储/业务层使用；不包含 Room 或同步存储字段。时间使用 UTC 毫秒。 */
data class WorkSession(
    val id: String,
    val taskId: String?,
    val startsAt: Long,
    val endsAt: Long,
    val ownerUserId: String = "00000000-0000-0000-0000-000000000001",
    val stageId: String? = null,
    val note: String? = null,
    val status: WorkSessionStatus = WorkSessionStatus.PLANNED,
    val isLocked: Boolean = false,
    val overlapConfirmed: Boolean = false,
    val detachedAt: Long? = null,
    val detachReason: String? = null,
    val focusWindowStart: Long? = null,
    val focusWindowEnd: Long? = null,
    val focusTargetSeconds: Long? = null,
)

enum class WorkSessionStatus { PLANNED, COMPLETED, CANCELLED }
