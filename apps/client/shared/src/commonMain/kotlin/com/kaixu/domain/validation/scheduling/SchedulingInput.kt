package com.kaixu.domain.validation.scheduling

import com.kaixu.core.platform.CalendarDate
import com.kaixu.core.platform.LocalZone
import com.kaixu.domain.model.scheduling.*
import com.kaixu.domain.model.tasks.TaskDeadline

/** 仓储与用例入口复用的纯输入检查与时间规则判定，不依赖 Room。跨记录约束仍由事务与 DAO 校验。 */
object SchedulingInput {
    fun requireId(value: String) = require(value.isNotBlank()) { "ID 不能为空" }
    fun requireRange(start: Long, end: Long) = require(end > start) { "结束时间必须晚于开始时间" }

    fun requirePositiveFocusTarget(focusTargetSeconds: Long?) {
        require(focusTargetSeconds == null || focusTargetSeconds > 0L) { "计时目标必须为正数" }
    }

    /** 半开区间 [aStart, aEnd) 与 [bStart, bEnd) 是否存在严格交集；相邻首尾 [a, b) 与 [b, c) 不算重叠。 */
    fun intervalsOverlap(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Boolean {
        requireRange(aStart, aEnd)
        requireRange(bStart, bEnd)
        return aStart < bEnd && bStart < aEnd
    }

    /**
     * 判断半开区间 [startsAt, endsAt) 是否晚于任务截止要求：
     * - [TaskDeadline.None]：永不超期
     * - [TaskDeadline.ExactTime]：`endsAt > epochMillis` 时超期（恰好等于截止时刻不算超期）
     * - [TaskDeadline.DateOnly]：按 [zone] 覆盖截止自然日整日，`endsAt - 1` 落在截止日之后才算超期（结束恰好等于次日零点不算超期）
     */
    fun exceedsDeadline(
        startsAt: Long,
        endsAt: Long,
        deadline: TaskDeadline,
        zone: LocalZone = LocalZone.systemDefault(),
    ): Boolean {
        requireRange(startsAt, endsAt)
        return when (deadline) {
            TaskDeadline.None -> false
            is TaskDeadline.ExactTime -> endsAt > deadline.epochMillis
            is TaskDeadline.DateOnly ->
                CalendarDate.compareDates(zone.localDateOf(endsAt - 1L), deadline.date) > 0
        }
    }

    fun normalizedDraft(draft: WorkSessionDraft): WorkSessionDraft {
        requireId(draft.id)
        requireId(draft.taskId)
        requireRange(draft.startsAt, draft.endsAt)
        requirePositiveFocusTarget(draft.focusTargetSeconds)
        return draft.copy(note = draft.note?.trim()?.takeIf { it.isNotEmpty() })
    }

    fun normalizedEditDraft(draft: WorkSessionEditDraft): WorkSessionEditDraft {
        requireRange(draft.startsAt, draft.endsAt)
        requirePositiveFocusTarget(draft.focusTargetSeconds)
        return draft.copy(note = draft.note?.trim()?.takeIf { it.isNotEmpty() })
    }

    fun normalizedReschedule(reschedule: WorkSessionReschedule): WorkSessionReschedule {
        requireRange(reschedule.startsAt, reschedule.endsAt)
        return reschedule
    }

    fun toNewWorkSession(draft: WorkSessionDraft, ownerUserId: String): WorkSession {
        val clean = normalizedDraft(draft)
        return workSession(
            WorkSession(
                id = clean.id,
                taskId = clean.taskId,
                startsAt = clean.startsAt,
                endsAt = clean.endsAt,
                ownerUserId = ownerUserId,
                note = clean.note,
                isLocked = clean.isLocked,
                focusTargetSeconds = clean.focusTargetSeconds,
            ),
        )
    }

    fun workSession(value: WorkSession): WorkSession {
        requireId(value.id)
        requireId(value.ownerUserId)
        value.taskId?.let(::requireId)
        value.stageId?.let(::requireId)
        requireRange(value.startsAt, value.endsAt)
        requirePositiveFocusTarget(value.focusTargetSeconds)
        require(if (value.taskId != null) value.detachedAt == null && value.detachReason == null
            else value.detachedAt != null && !value.detachReason.isNullOrBlank() && value.stageId == null) { "任务关联与脱链信息不一致" }
        return value.copy(note = value.note?.trim()?.takeIf { it.isNotEmpty() })
    }

    fun focusSession(value: FocusSession): FocusSession {
        requireId(value.id)
        requireId(value.ownerUserId)
        requireId(value.workSessionId)
        val terminal = value.status == FocusSessionStatus.ENDED || value.status == FocusSessionStatus.INTERRUPTED
        require(terminal == (value.endedAt != null)) { "会话状态与结束时间不一致" }
        require(value.endedAt == null || value.endedAt >= value.startedAt) { "会话结束不能早于开始" }
        return value.copy(feedback = value.feedback?.trim()?.takeIf { it.isNotEmpty() })
    }

    fun segment(value: FocusSegment): FocusSegment {
        requireId(value.id)
        requireId(value.sessionId)
        requireId(value.clientSegmentId)
        requireRange(value.startsAt, value.endsAt)
        return value
    }
}
