package com.kaixu.domain.model.scheduling

/** 计划执行任务的时段；实际计时另存 FocusSession / FocusSegment。时间使用 UTC 毫秒。 */
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
    /** 个人计时目标；关联的有效片段均计入，不按计划窗口截断，也不以目标封顶。 */
    val focusTargetSeconds: Long? = null,
)

enum class WorkSessionStatus { PLANNED, COMPLETED, CANCELLED }
