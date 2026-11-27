package com.kaixu.domain.usecase.scheduling

import com.kaixu.core.platform.LocalZone
import com.kaixu.domain.model.scheduling.FocusDuration
import com.kaixu.domain.model.scheduling.FocusSegment
import com.kaixu.domain.model.scheduling.FocusSession
import com.kaixu.domain.model.scheduling.FocusSessionStatus
import com.kaixu.domain.model.scheduling.FocusSummary
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.model.scheduling.isDetached
import com.kaixu.domain.repository.FocusRepository
import com.kaixu.domain.repository.FocusWriteScope
import com.kaixu.domain.repository.WorkSessionRepository
import com.kaixu.domain.validation.scheduling.SchedulingInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * 启动一次实际专注计时：
 * 支持客户端稳定 `sessionId` 幂等重试；若计划时段尚未设置 `focusTargetSeconds`，
 * 在同一写事务内原子补写默认目标秒数并创建 `FocusSession`，任一步失败均整体回滚且不递增 `revision`。
 */
class StartFocusSessionCommandUseCase(
    private val repository: FocusRepository,
    private val fallbackPlans: WorkSessionRepository? = null,
) {
    suspend operator fun invoke(
        workSessionId: String,
        startedAt: Long,
        sessionId: String = newSchedulingId(),
    ): FocusSession {
        SchedulingInput.requireId(workSessionId)
        SchedulingInput.requireId(sessionId)
        return repository.writeTransaction {
            val existing = findSessionById(sessionId)
            if (existing != null) {
                require(existing.workSessionId == workSessionId && existing.startedAt == startedAt) {
                    "同一会话 ID 不能对应不同启动请求"
                }
                return@writeTransaction existing
            }
            val plan = requireNotNull(
                findWorkSessionById(workSessionId) ?: fallbackPlans?.findById(workSessionId),
            ) { "计划时段不存在或不属于本人" }
            require(plan.status == WorkSessionStatus.PLANNED) { "只能为计划中的时段开始计时" }
            val linkedTaskId = requireNotNull(plan.taskId?.takeIf { !plan.isDetached }) {
                "脱链时段不能新开计时"
            }
            requirePlannableTask(linkedTaskId, fallbackPlans)
            if (plan.focusTargetSeconds == null) {
                val target = ((plan.endsAt - plan.startsAt) / 1000L).coerceAtLeast(1L)
                val updatedPlan = plan.copy(focusTargetSeconds = target)
                val savedTarget = if (findWorkSessionById(workSessionId) != null) {
                    updateWorkSession(updatedPlan)
                } else {
                    fallbackPlans?.update(updatedPlan) == true
                }
                check(savedTarget) { "无法保存本次计时目标" }
            }
            insertSession(
                FocusSession(
                    id = sessionId,
                    workSessionId = plan.id,
                    startedAt = startedAt,
                    ownerUserId = ownerUserId,
                ),
            )
        }
    }
}

/** 暂停计时并可选在同一写事务内提交最后一个已确认的有效片段。 */
class PauseFocusSessionCommandUseCase(
    private val repository: FocusRepository,
) {
    suspend operator fun invoke(
        sessionId: String,
        finalSegment: FocusSegment? = null,
    ): FocusSession {
        SchedulingInput.requireId(sessionId)
        return repository.writeTransaction {
            val current = requireOwnedSession(sessionId)
            require(current.status == FocusSessionStatus.ACTIVE || current.status == FocusSessionStatus.PAUSED) {
                "已结束的会话不能暂停"
            }
            appendFinalSegmentIfPresent(current, finalSegment)
            val proposed = current.copy(status = FocusSessionStatus.PAUSED)
            check(replaceSession(proposed)) { "计时状态保存失败" }
            requireNotNull(findSessionById(current.id))
        }
    }
}

/** 恢复已暂停计时：重检计划时段与关联任务/工作区是否仍处于可执行状态。 */
class ResumeFocusSessionCommandUseCase(
    private val repository: FocusRepository,
    private val fallbackPlans: WorkSessionRepository? = null,
) {
    suspend operator fun invoke(sessionId: String): FocusSession {
        SchedulingInput.requireId(sessionId)
        return repository.writeTransaction {
            val current = requireOwnedSession(sessionId)
            require(current.status == FocusSessionStatus.PAUSED || current.status == FocusSessionStatus.ACTIVE) {
                "已结束的会话不能继续"
            }
            val plan = requireNotNull(
                findWorkSessionById(current.workSessionId) ?: fallbackPlans?.findById(current.workSessionId),
            ) { "计划时段不存在或不属于本人" }
            require(plan.status == WorkSessionStatus.PLANNED) { "计划时段已不可执行" }
            val linkedTaskId = requireNotNull(plan.taskId?.takeIf { !plan.isDetached }) {
                "脱链时段不能继续计时"
            }
            requirePlannableTask(linkedTaskId, fallbackPlans)
            val proposed = current.copy(status = FocusSessionStatus.ACTIVE)
            check(replaceSession(proposed)) { "计时状态保存失败" }
            requireNotNull(findSessionById(current.id))
        }
    }
}

/** 正常结束或中断计时会话：在同一写事务内追加末尾片段并写入结束状态，任一步失败整体回滚。 */
class FinishFocusSessionCommandUseCase(
    private val repository: FocusRepository,
) {
    suspend operator fun invoke(
        sessionId: String,
        endedAt: Long,
        feedback: String? = null,
        finalSegment: FocusSegment? = null,
        interrupted: Boolean = false,
    ): FocusSession {
        SchedulingInput.requireId(sessionId)
        return repository.writeTransaction {
            val current = requireOwnedSession(sessionId)
            val status = if (interrupted) FocusSessionStatus.INTERRUPTED else FocusSessionStatus.ENDED
            val proposed = SchedulingInput.focusSession(
                current.copy(
                    status = status,
                    endedAt = endedAt,
                    feedback = feedback,
                ),
            )
            if (current.status == FocusSessionStatus.ENDED || current.status == FocusSessionStatus.INTERRUPTED) {
                require(current == proposed) { "已结束会话不能重写结束结果" }
            }
            appendFinalSegmentIfPresent(current, finalSegment)
            check(replaceSession(proposed)) { "计时状态保存失败" }
            requireNotNull(findSessionById(current.id))
        }
    }
}

/** 追加已确认的闭合有效计时片段，仅允许运行中会话提交新片段，相同 `clientSegmentId` 幂等重试。 */
class RecordFocusSegmentCommandUseCase(
    private val repository: FocusRepository,
) {
    suspend operator fun invoke(segment: FocusSegment): FocusSegment {
        val checked = SchedulingInput.segment(segment)
        return repository.writeTransaction {
            val session = requireOwnedSession(checked.sessionId)
            val existing = listSegments(session.id).firstOrNull { it.clientSegmentId == checked.clientSegmentId }
            require(session.status == FocusSessionStatus.ACTIVE || existing != null) {
                "仅运行中的会话允许提交新的有效片段"
            }
            insertSegment(checked)
        }
    }
}

/** 汇总指定个人工作时段下的全部本人计时会话与累计有效时长（含软删除或脱链后的历史记录）。 */
class GetFocusSummaryQueryUseCase(
    private val repository: FocusRepository,
) {
    suspend operator fun invoke(workSessionId: String): FocusSummary {
        SchedulingInput.requireId(workSessionId)
        return FocusSummary(
            workSessionId = workSessionId,
            sessions = repository.listSessions(workSessionId),
            duration = repository.getDuration(workSessionId),
        )
    }

    fun observe(workSessionId: String): Flow<FocusSummary> =
        repository.revision.map { invoke(workSessionId) }
}

/** 专注计时领域的统一用例门面；共用同一个本地修订信号与时区配置。 */
class FocusUseCases(
    private val repository: FocusRepository,
    val defaultZone: LocalZone = LocalZone.systemDefault(),
    workSessionRepository: WorkSessionRepository? = null,
) {
    val start = StartFocusSessionCommandUseCase(repository, workSessionRepository)
    val pause = PauseFocusSessionCommandUseCase(repository)
    val resume = ResumeFocusSessionCommandUseCase(repository, workSessionRepository)
    val finish = FinishFocusSessionCommandUseCase(repository)
    val recordSegment = RecordFocusSegmentCommandUseCase(repository)
    val getSummary = GetFocusSummaryQueryUseCase(repository)

    val revision: StateFlow<Long> get() = repository.revision

    suspend fun findSessionById(id: String): FocusSession? = repository.findSessionById(id)

    suspend fun listSessions(workSessionId: String): List<FocusSession> = repository.listSessions(workSessionId)

    suspend fun listSegments(sessionId: String): List<FocusSegment> = repository.listSegments(sessionId)

    suspend fun getDuration(workSessionId: String): FocusDuration = repository.getDuration(workSessionId)
}

private suspend fun FocusWriteScope.requireOwnedSession(id: String): FocusSession =
    requireNotNull(findSessionById(id)) { "计时会话不存在或不属于本人" }

private suspend fun FocusWriteScope.appendFinalSegmentIfPresent(
    current: FocusSession,
    value: FocusSegment?,
) {
    if (value == null) return
    val checked = SchedulingInput.segment(value)
    require(checked.sessionId == current.id) { "末尾片段必须属于当前会话" }
    val existing = listSegments(current.id).firstOrNull { it.clientSegmentId == checked.clientSegmentId }
    require(current.status == FocusSessionStatus.ACTIVE || existing != null) {
        "暂停或结束后不能补造新的有效计时"
    }
    insertSegment(checked)
}
