package com.kaixu.domain.usecase.scheduling

import com.kaixu.domain.model.scheduling.*
import com.kaixu.domain.repository.*

class StartFocusSessionUseCase(
    private val plans: WorkSessionRepository,
    private val focus: FocusRepository,
    private val transaction: SchedulingTransaction,
) {
    /** 调用方保存稳定 sessionId 用于重试；实际开始时间允许在计划窗口外。 */
    suspend operator fun invoke(workSessionId: String, startedAt: Long, sessionId: String = newSchedulingId()): FocusSession = transaction.run {
        val existing = focus.findSessionById(sessionId)
        if (existing != null) {
            require(existing.workSessionId == workSessionId && existing.startedAt == startedAt) { "同一会话 ID 不能对应不同启动请求" }
            return@run existing
        }
        val plan = requireNotNull(plans.findById(workSessionId)) { "计划时段不存在或不属于本人" }
        require(plan.status == WorkSessionStatus.PLANNED) { "只能为计划中的时段开始计时" }
        requirePlannableTask(plans, requireNotNull(plan.taskId) { "脱链时段不能新开计时" })
        if (plan.focusTargetSeconds == null) {
            val target = ((plan.endsAt - plan.startsAt) / 1000).coerceAtLeast(1)
            check(plans.update(plan.copy(focusTargetSeconds = target))) { "无法保存本次计时目标" }
        }
        focus.createSession(FocusSession(sessionId, plan.id, startedAt, ownerUserId = focus.ownerUserId))
    }
}

/** 暂停、继续和结束只提交已经确认的有效片段，不根据墙上时钟补算未提交时间。 */
class ControlFocusSessionUseCase(
    private val plans: WorkSessionRepository,
    private val focus: FocusRepository,
    private val transaction: SchedulingTransaction,
) {
    suspend fun pause(sessionId: String, finalSegment: FocusSegment? = null): FocusSession = transaction.run {
        val current = requireSession(sessionId)
        require(current.status == FocusSessionStatus.ACTIVE || current.status == FocusSessionStatus.PAUSED) { "已结束的会话不能暂停" }
        appendFinal(current, finalSegment)
        save(current.copy(status = FocusSessionStatus.PAUSED))
    }

    suspend fun resume(sessionId: String): FocusSession = transaction.run {
        val current = requireSession(sessionId)
        require(current.status == FocusSessionStatus.PAUSED || current.status == FocusSessionStatus.ACTIVE) { "已结束的会话不能继续" }
        val plan = requireNotNull(plans.findById(current.workSessionId)) { "计划时段不存在或不属于本人" }
        require(plan.status == WorkSessionStatus.PLANNED) { "计划时段已不可执行" }
        requirePlannableTask(plans, requireNotNull(plan.taskId) { "脱链时段不能继续计时" })
        save(current.copy(status = FocusSessionStatus.ACTIVE))
    }

    suspend fun finish(
        sessionId: String,
        endedAt: Long,
        feedback: String? = null,
        finalSegment: FocusSegment? = null,
        interrupted: Boolean = false,
    ): FocusSession = transaction.run {
        val current = requireSession(sessionId)
        val status = if (interrupted) FocusSessionStatus.INTERRUPTED else FocusSessionStatus.ENDED
        val proposed = current.copy(status = status, endedAt = endedAt, feedback = feedback?.takeUnless { it.isBlank() })
        if (current.status == FocusSessionStatus.ENDED || current.status == FocusSessionStatus.INTERRUPTED) {
            require(current == proposed) { "已结束会话不能重写结束结果" }
        }
        appendFinal(current, finalSegment)
        save(proposed)
    }

    private suspend fun requireSession(id: String) = requireNotNull(focus.findSessionById(id)) { "计时会话不存在或不属于本人" }

    private suspend fun appendFinal(current: FocusSession, value: FocusSegment?) {
        if (value == null) return
        require(value.sessionId == current.id) { "末尾片段必须属于当前会话" }
        val existing = focus.listSegments(current.id).firstOrNull { it.clientSegmentId == value.clientSegmentId }
        require(current.status == FocusSessionStatus.ACTIVE || existing != null) { "暂停或结束后不能补造新的有效计时" }
        focus.appendSegment(value)
    }

    private suspend fun save(value: FocusSession): FocusSession {
        check(focus.updateSession(value)) { "计时状态保存失败" }
        return requireNotNull(focus.findSessionById(value.id))
    }
}

class RecordFocusSegmentUseCase(private val focus: FocusRepository, private val transaction: SchedulingTransaction) {
    suspend operator fun invoke(segment: FocusSegment): FocusSegment = transaction.run {
        val session = requireNotNull(focus.findSessionById(segment.sessionId)) { "计时会话不存在或不属于本人" }
        val existing = focus.listSegments(session.id).firstOrNull { it.clientSegmentId == segment.clientSegmentId }
        require(session.status == FocusSessionStatus.ACTIVE || existing != null) { "仅运行中的会话允许提交新的有效片段" }
        focus.appendSegment(segment)
    }
}

data class FocusSummary(
    val workSessionId: String,
    val sessions: List<FocusSession>,
    val duration: FocusDuration,
)

/** 汇总所有本人会话，包含窗口外计时及软删除计划的历史；不自动完成任务或时段。 */
class GetFocusSummaryUseCase(private val focus: FocusRepository, private val transaction: SchedulingTransaction) {
    suspend operator fun invoke(workSessionId: String): FocusSummary = transaction.run {
        FocusSummary(workSessionId, focus.listSessions(workSessionId), focus.getDuration(workSessionId))
    }
}
