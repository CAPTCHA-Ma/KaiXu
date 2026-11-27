package com.kaixu.domain.usecase.scheduling

import com.kaixu.core.platform.LocalZone
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.scheduling.WorkSessionConfirmation
import com.kaixu.domain.model.scheduling.WorkSessionDraft
import com.kaixu.domain.model.scheduling.WorkSessionEditDraft
import com.kaixu.domain.model.scheduling.WorkSessionReschedule
import com.kaixu.domain.model.scheduling.WorkSessionSaveResult
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.repository.FocusRepository
import com.kaixu.domain.repository.WorkSessionRepository
import com.kaixu.domain.validation.scheduling.SchedulingInput

/**
 * 创建单条或批量个人工作时段：
 * 保留客户端稳定 ID 幂等重试语义（全部命中相同内容直接返回已保存项，部分命中或不同内容拒绝），
 * 在同一写事务内展开固定事件重复序列、检查已有时段与超截止警告，确认后写入 `overlapConfirmed`。
 */
class CreateWorkSessionsCommandUseCase(
    private val repository: WorkSessionRepository,
    private val defaultZone: LocalZone = LocalZone.systemDefault(),
) {
    suspend operator fun invoke(
        drafts: List<WorkSessionDraft>,
        confirmed: WorkSessionConfirmation? = null,
        zone: LocalZone = defaultZone,
    ): WorkSessionSaveResult {
        if (drafts.isEmpty()) return WorkSessionSaveResult.Saved(emptyList())
        val proposed = drafts.map { SchedulingInput.toNewWorkSession(it, repository.ownerUserId) }
        require(proposed.map { it.id }.distinct().size == proposed.size) { "批量时段 ID 不能重复" }

        return repository.writeTransaction {
            val existing = proposed.map { findById(it.id) }
            if (existing.any { it != null }) {
                require(existing.all { it != null }) { "批量请求中的部分时段 ID 已被使用，请重新核对" }
                val saved = existing.filterNotNull()
                require(saved.zip(proposed).all { (old, value) -> old.copy(overlapConfirmed = false) == value }) {
                    "同一时段 ID 已用于不同的排期内容，请刷新"
                }
                return@writeTransaction WorkSessionSaveResult.Saved(saved)
            }

            val review = WorkSessionConflictChecker.review(
                scope = this,
                proposed = proposed,
                excludeIds = emptySet(),
                targetKey = "create",
                zone = zone,
            )
            if (review.requiresConfirmation && confirmed?.confirmationKey != review.confirmationKey) {
                WorkSessionSaveResult.NeedsConfirmation(review)
            } else {
                WorkSessionSaveResult.Saved(insertSessions(WorkSessionConflictChecker.accepted(review)))
            }
        }
    }

    suspend operator fun invoke(
        draft: WorkSessionDraft,
        confirmed: WorkSessionConfirmation? = null,
        zone: LocalZone = defaultZone,
    ): WorkSessionSaveResult = invoke(listOf(draft), confirmed, zone)
}

/**
 * 按 ID 编辑个人工作时段：
 * 支持可选 [expectedOriginal] 乐观并发核对；若已存在计时历史，禁止修改 `focusTargetSeconds`，
 * 但仍允许调整计划时间、说明与锁定标记，且始终保留原 `stageId`。
 */
class UpdateWorkSessionCommandUseCase(
    private val repository: WorkSessionRepository,
    private val defaultZone: LocalZone = LocalZone.systemDefault(),
    private val fallbackFocus: FocusRepository? = null,
) {
    suspend operator fun invoke(
        sessionId: String,
        draft: WorkSessionEditDraft,
        expectedOriginal: WorkSession? = null,
        confirmed: WorkSessionConfirmation? = null,
        zone: LocalZone = defaultZone,
    ): WorkSessionSaveResult {
        SchedulingInput.requireId(sessionId)
        if (expectedOriginal != null) {
            require(expectedOriginal.id == sessionId) { "基准时段 ID 与目标时段不一致" }
        }
        val clean = SchedulingInput.normalizedEditDraft(draft)
        return repository.writeTransaction {
            val current = requireNotNull(findById(sessionId)) { "计划时段不存在或不属于本人" }
            if (expectedOriginal != null) {
                require(current == expectedOriginal) { "计划时段已变更，请刷新后重新核对" }
            }
            requireEditableSession(current)
            val proposed = SchedulingInput.workSession(
                current.copy(
                    startsAt = clean.startsAt,
                    endsAt = clean.endsAt,
                    note = clean.note,
                    isLocked = clean.isLocked,
                    focusTargetSeconds = clean.focusTargetSeconds,
                    overlapConfirmed = false,
                ),
            )
            val hasFocusHistory = listFocusSessions(current.id).isNotEmpty() ||
                fallbackFocus?.listSessions(current.id)?.isNotEmpty() == true
            if (hasFocusHistory) {
                require(proposed.focusTargetSeconds == current.focusTargetSeconds) { "开始计时后不能改写计时目标" }
            }
            val review = WorkSessionConflictChecker.review(
                scope = this,
                proposed = listOf(proposed),
                excludeIds = setOf(current.id),
                targetKey = "update:${current.id}",
                zone = zone,
            )
            if (review.requiresConfirmation && confirmed?.confirmationKey != review.confirmationKey) {
                WorkSessionSaveResult.NeedsConfirmation(review)
            } else {
                val accepted = WorkSessionConflictChecker.accepted(review).single()
                check(replaceSession(accepted)) { "计划时段保存失败" }
                WorkSessionSaveResult.Saved(listOf(accepted))
            }
        }
    }

    suspend operator fun invoke(
        sessionId: String,
        draft: WorkSessionDraft,
        expectedOriginal: WorkSession? = null,
        confirmed: WorkSessionConfirmation? = null,
        zone: LocalZone = defaultZone,
    ): WorkSessionSaveResult {
        SchedulingInput.requireId(sessionId)
        require(draft.id == sessionId) { "编辑不能改变时段身份或任务归属" }
        val clean = SchedulingInput.normalizedDraft(draft)
        return repository.writeTransaction {
            val current = requireNotNull(findById(sessionId)) { "计划时段不存在或不属于本人" }
            if (expectedOriginal != null) {
                require(expectedOriginal.id == sessionId && current == expectedOriginal) {
                    "计划时段已变更，请刷新后重新核对"
                }
            }
            require(clean.taskId == current.taskId) { "编辑不能改变时段身份或任务归属" }
            requireEditableSession(current)
            val proposed = SchedulingInput.workSession(
                current.copy(
                    startsAt = clean.startsAt,
                    endsAt = clean.endsAt,
                    note = clean.note,
                    isLocked = clean.isLocked,
                    focusTargetSeconds = clean.focusTargetSeconds,
                    overlapConfirmed = false,
                ),
            )
            val hasFocusHistory = listFocusSessions(current.id).isNotEmpty() ||
                fallbackFocus?.listSessions(current.id)?.isNotEmpty() == true
            if (hasFocusHistory) {
                require(proposed.focusTargetSeconds == current.focusTargetSeconds) { "开始计时后不能改写计时目标" }
            }
            val review = WorkSessionConflictChecker.review(
                scope = this,
                proposed = listOf(proposed),
                excludeIds = setOf(current.id),
                targetKey = "update:${current.id}",
                zone = zone,
            )
            if (review.requiresConfirmation && confirmed?.confirmationKey != review.confirmationKey) {
                WorkSessionSaveResult.NeedsConfirmation(review)
            } else {
                val accepted = WorkSessionConflictChecker.accepted(review).single()
                check(replaceSession(accepted)) { "计划时段保存失败" }
                WorkSessionSaveResult.Saved(listOf(accepted))
            }
        }
    }
}

/** 面向日历拖拽与边缘拉伸的轻量改期命令：仅更新起止时刻，复用完整权限、冲突与截止审查。 */
class RescheduleWorkSessionCommandUseCase(
    private val repository: WorkSessionRepository,
    private val defaultZone: LocalZone = LocalZone.systemDefault(),
) {
    suspend operator fun invoke(
        sessionId: String,
        startsAt: Long,
        endsAt: Long,
        expectedOriginal: WorkSession? = null,
        confirmed: WorkSessionConfirmation? = null,
        zone: LocalZone = defaultZone,
    ): WorkSessionSaveResult {
        SchedulingInput.requireId(sessionId)
        SchedulingInput.requireRange(startsAt, endsAt)
        if (expectedOriginal != null) {
            require(expectedOriginal.id == sessionId) { "基准时段 ID 与目标时段不一致" }
        }
        return repository.writeTransaction {
            val current = requireNotNull(findById(sessionId)) { "计划时段不存在或不属于本人" }
            if (expectedOriginal != null) {
                require(current == expectedOriginal) { "计划时段已变更，请刷新后重新核对" }
            }
            requireEditableSession(current)
            val proposed = SchedulingInput.workSession(
                current.copy(
                    startsAt = startsAt,
                    endsAt = endsAt,
                    overlapConfirmed = false,
                ),
            )
            val review = WorkSessionConflictChecker.review(
                scope = this,
                proposed = listOf(proposed),
                excludeIds = setOf(current.id),
                targetKey = "reschedule:${current.id}",
                zone = zone,
            )
            if (review.requiresConfirmation && confirmed?.confirmationKey != review.confirmationKey) {
                WorkSessionSaveResult.NeedsConfirmation(review)
            } else {
                val accepted = WorkSessionConflictChecker.accepted(review).single()
                check(replaceSession(accepted)) { "计划时段保存失败" }
                WorkSessionSaveResult.Saved(listOf(accepted))
            }
        }
    }

    suspend operator fun invoke(
        sessionId: String,
        reschedule: WorkSessionReschedule,
        expectedOriginal: WorkSession? = null,
        confirmed: WorkSessionConfirmation? = null,
        zone: LocalZone = defaultZone,
    ): WorkSessionSaveResult {
        val clean = SchedulingInput.normalizedReschedule(reschedule)
        return invoke(sessionId, clean.startsAt, clean.endsAt, expectedOriginal, confirmed, zone)
    }
}

/**
 * 变更个人工作时段状态（仅允许 `PLANNED -> COMPLETED | CANCELLED`）：
 * 不自动恢复为 `PLANNED`，且绝不自动完成关联任务；结束时段会由 DAO 原子中断运行或暂停中的计时。
 */
class ChangeWorkSessionStatusCommandUseCase(
    private val repository: WorkSessionRepository,
) {
    suspend operator fun invoke(
        sessionId: String,
        status: WorkSessionStatus,
        expectedOriginal: WorkSession? = null,
    ): WorkSession {
        SchedulingInput.requireId(sessionId)
        require(status != WorkSessionStatus.PLANNED) { "不自动恢复已结束或取消的安排，请另行创建" }
        if (expectedOriginal != null) {
            require(expectedOriginal.id == sessionId) { "基准时段 ID 与目标时段不一致" }
        }
        return repository.writeTransaction {
            val current = requireNotNull(findById(sessionId)) { "计划时段不存在或不属于本人" }
            if (expectedOriginal != null) {
                require(current == expectedOriginal) { "计划时段已变更，请刷新" }
            }
            requireTransitionableSession(current)
            if (current.status == status) return@writeTransaction current
            require(current.status == WorkSessionStatus.PLANNED) { "只能结束计划中的时段" }
            val saved = current.copy(status = status)
            check(replaceSession(saved)) { "计划时段保存失败" }
            saved
        }
    }
}

/** 按 ID 软删除本人个人工作时段（含脱链时段）；由 DAO 原子中断活动计时并保留有效计时历史。 */
class DeleteWorkSessionCommandUseCase(
    private val repository: WorkSessionRepository,
) {
    suspend operator fun invoke(
        sessionId: String,
        expectedOriginal: WorkSession? = null,
    ): Boolean {
        if (sessionId.isBlank()) return false
        if (expectedOriginal != null) {
            require(expectedOriginal.id == sessionId) { "基准时段 ID 与目标时段不一致" }
        }
        return repository.writeTransaction {
            val current = findById(sessionId) ?: return@writeTransaction false
            if (expectedOriginal != null) {
                require(current == expectedOriginal) { "计划时段已变更，请刷新后重新确认删除" }
            }
            removeSessionById(current.id)
        }
    }
}
