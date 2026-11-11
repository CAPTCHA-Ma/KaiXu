package com.kaixu.domain.validation.scheduling

import com.kaixu.domain.model.scheduling.*

/** 仓储入口复用的纯输入检查，不依赖 Room。跨记录约束仍由事务与 DAO 校验。 */
object SchedulingInput {
    fun requireId(value: String) = require(value.isNotBlank()) { "ID 不能为空" }
    fun requireRange(start: Long, end: Long) = require(end > start) { "结束时间必须晚于开始时间" }

    fun workSession(value: WorkSession): WorkSession {
        requireId(value.id)
        requireId(value.ownerUserId)
        value.taskId?.let(::requireId)
        value.stageId?.let(::requireId)
        requireRange(value.startsAt, value.endsAt)
        require(value.focusTargetSeconds == null || value.focusTargetSeconds > 0) { "计时目标必须为正数" }
        require(if (value.taskId != null) value.detachedAt == null && value.detachReason == null
            else value.detachedAt != null && !value.detachReason.isNullOrBlank() && value.stageId == null) { "任务关联与脱链信息不一致" }
        return value.copy(note = value.note?.takeUnless { it.isBlank() })
    }

    fun focusSession(value: FocusSession): FocusSession {
        requireId(value.id)
        requireId(value.ownerUserId)
        requireId(value.workSessionId)
        val terminal = value.status == FocusSessionStatus.ENDED || value.status == FocusSessionStatus.INTERRUPTED
        require(terminal == (value.endedAt != null)) { "会话状态与结束时间不一致" }
        require(value.endedAt == null || value.endedAt >= value.startedAt) { "会话结束不能早于开始" }
        return value.copy(feedback = value.feedback?.takeUnless { it.isBlank() })
    }

    fun segment(value: FocusSegment): FocusSegment {
        requireId(value.id)
        requireId(value.sessionId)
        requireId(value.clientSegmentId)
        requireRange(value.startsAt, value.endsAt)
        return value
    }
}
