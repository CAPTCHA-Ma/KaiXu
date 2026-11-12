package com.kaixu.domain.usecase.scheduling

import com.kaixu.domain.model.scheduling.*
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.tasks.TaskStatus
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.repository.*
import com.kaixu.domain.validation.scheduling.SchedulingInput

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
internal fun newSchedulingId(): String = kotlin.uuid.Uuid.random().toString()

/** 表单不接受所有者、脱链信息或任意状态，避免将持久化对象当作创建命令。 */
data class WorkSessionDraft(
    val taskId: String,
    val startsAt: Long,
    val endsAt: Long,
    val id: String = newSchedulingId(),
    val note: String? = null,
    val isLocked: Boolean = false,
    val focusTargetSeconds: Long? = null,
) {
    internal fun toSession(ownerId: String) = SchedulingInput.workSession(WorkSession(
        id = id, taskId = taskId, startsAt = startsAt, endsAt = endsAt, ownerUserId = ownerId,
        note = note, isLocked = isLocked, focusTargetSeconds = focusTargetSeconds,
    ))
}

data class ProposedSessionOverlap(val firstId: String, val secondId: String)

/** 确认绑定表单及当次约束快照；新冲突或截止变更后返回新快照，不能沿用旧确认。 */
data class WorkSessionConfirmation(
    val proposed: List<WorkSession>,
    val tasks: List<Task>,
    val workspaces: List<Workspace>,
    val events: List<FixedEvent>,
    val sessions: List<WorkSession>,
    val proposedOverlaps: List<ProposedSessionOverlap>,
    val pastDeadlineIds: List<String>,
)

sealed interface WorkSessionSaveResult {
    data class Saved(val sessions: List<WorkSession>) : WorkSessionSaveResult
    data class NeedsConfirmation(val confirmation: WorkSessionConfirmation) : WorkSessionSaveResult
}

internal suspend fun requirePlannableTask(repository: WorkSessionRepository, taskId: String): Pair<Task, Workspace?> {
    val task = requireNotNull(repository.findTaskById(taskId)) { "任务不存在或不可访问" }
    require(task.status != TaskStatus.COMPLETED) { "已完成任务不能新增或调整执行安排" }
    val workspace = task.workspaceId?.let { id ->
        requireNotNull(repository.findWorkspaceById(id)) { "所属工作区不存在或不可访问" }.also {
            require(it.ownerUserId == repository.ownerUserId && it.status == WorkspaceStatus.ACTIVE) { "所属工作区已归档或不可编辑" }
        }
    }
    require(workspace != null || task.createdBy == repository.ownerUserId) { "只能安排本人可访问的任务" }
    return task to workspace
}

internal class WorkSessionSchedulingRules(
    private val repository: WorkSessionRepository,
    /** 按用户所选时区返回 YYYY-MM-DD；日期截止不能擅自补成某个时刻。 */
    private val dateAt: (Long) -> String,
) {
    suspend fun review(proposed: List<WorkSession>, excludeIds: Set<String>): WorkSessionConfirmation {
        val contexts = proposed.mapNotNull { it.taskId }.distinct().sorted().map { requirePlannableTask(repository, it) }
        val tasks = contexts.map { it.first }
        val workspaces = contexts.mapNotNull { it.second }.distinctBy { it.id }.sortedBy { it.id }
        val events = proposed.flatMap { repository.listFixedEventsInRange(it.startsAt, it.endsAt) }.distinctBy { it.id }.sortedBy { it.id }
        val sessions = proposed.flatMap { repository.listInRange(it.startsAt, it.endsAt) }
            .filter { it.id !in excludeIds && it.status != WorkSessionStatus.CANCELLED }.distinctBy { it.id }.sortedBy { it.id }
        val overlaps = buildList {
            for (i in proposed.indices) for (j in i + 1 until proposed.size) {
                val first = proposed[i]
                val second = proposed[j]
                if (first.startsAt < second.endsAt && first.endsAt > second.startsAt) add(ProposedSessionOverlap(first.id, second.id))
            }
        }
        val pastDeadline = proposed.filter { session ->
            val task = tasks.first { it.id == session.taskId }
            val deadlineAt = task.deadlineAt
            val deadlineDate = task.deadlineDate
            (deadlineAt != null && session.endsAt > deadlineAt) ||
                (deadlineDate != null && dateAt(session.endsAt - 1) > deadlineDate)
        }.map { it.id }
        return WorkSessionConfirmation(proposed, tasks, workspaces, events, sessions, overlaps, pastDeadline)
    }

    fun requiresConfirmation(review: WorkSessionConfirmation): Boolean =
        review.events.isNotEmpty() || review.sessions.isNotEmpty() || review.proposedOverlaps.isNotEmpty() || review.pastDeadlineIds.isNotEmpty()

    fun accepted(review: WorkSessionConfirmation): List<WorkSession> = review.proposed.map { value ->
        value.copy(overlapConfirmed = review.events.any { it.startsAt < value.endsAt && it.endsAt > value.startsAt } ||
            review.sessions.any { it.startsAt < value.endsAt && it.endsAt > value.startsAt } ||
            review.proposedOverlaps.any { it.firstId == value.id || it.secondId == value.id })
    }
}

class CreateWorkSessionsUseCase(
    private val repository: WorkSessionRepository,
    private val transaction: SchedulingTransaction,
    dateAt: (Long) -> String,
) {
    private val rules = WorkSessionSchedulingRules(repository, dateAt)

    suspend operator fun invoke(drafts: List<WorkSessionDraft>, confirmed: WorkSessionConfirmation? = null): WorkSessionSaveResult = transaction.run {
        if (drafts.isEmpty()) return@run WorkSessionSaveResult.Saved(emptyList())
        val proposed = drafts.map { it.toSession(repository.ownerUserId) }
        require(proposed.map { it.id }.distinct().size == proposed.size) { "批量时段 ID 不能重复" }
        val existing = proposed.map { repository.findById(it.id) }
        if (existing.any { it != null }) {
            require(existing.all { it != null }) { "批量请求中的部分时段 ID 已被使用，请重新核对" }
            val saved = existing.filterNotNull()
            require(saved.zip(proposed).all { (old, value) -> old.copy(overlapConfirmed = false) == value }) {
                "同一时段 ID 已用于不同的排期内容，请刷新"
            }
            return@run WorkSessionSaveResult.Saved(saved)
        }
        val review = rules.review(proposed, emptySet())
        if (rules.requiresConfirmation(review) && confirmed != review) WorkSessionSaveResult.NeedsConfirmation(review)
        else WorkSessionSaveResult.Saved(repository.createAll(rules.accepted(review)))
    }
}

/** 编辑固定身份与任务下的计划时间、说明、锁定和目标；原记录变化时不覆盖。 */
class UpdateWorkSessionUseCase(
    private val repository: WorkSessionRepository,
    private val focus: FocusRepository,
    private val transaction: SchedulingTransaction,
    dateAt: (Long) -> String,
) {
    private val rules = WorkSessionSchedulingRules(repository, dateAt)

    suspend operator fun invoke(original: WorkSession, draft: WorkSessionDraft, confirmed: WorkSessionConfirmation? = null): WorkSessionSaveResult = transaction.run {
        val current = requireNotNull(repository.findById(original.id)) { "计划时段不存在或不属于本人" }
        require(current == original) { "计划时段已变更，请刷新后重新核对" }
        require(current.status == WorkSessionStatus.PLANNED) { "只能修改计划中的时段" }
        require(draft.id == current.id && draft.taskId == current.taskId) { "编辑不能改变时段身份或任务归属" }
        val proposed = draft.toSession(repository.ownerUserId).copy(stageId = current.stageId)
        if (focus.listSessions(current.id).isNotEmpty()) {
            require(proposed.focusTargetSeconds == current.focusTargetSeconds) { "开始计时后不能改写计时目标" }
        }
        val review = rules.review(listOf(proposed), setOf(current.id))
        if (rules.requiresConfirmation(review) && confirmed != review) WorkSessionSaveResult.NeedsConfirmation(review)
        else {
            val accepted = rules.accepted(review).single()
            check(repository.update(accepted)) { "计划时段保存失败" }
            WorkSessionSaveResult.Saved(listOf(accepted))
        }
    }
}

/** 只变更个人时段状态，不据计时结果自动完成任务；取消或完成会由 DAO 原子中断活动计时。 */
class ChangeWorkSessionStatusUseCase(private val repository: WorkSessionRepository, private val transaction: SchedulingTransaction) {
    suspend operator fun invoke(original: WorkSession, status: WorkSessionStatus): WorkSession = transaction.run {
        require(status != WorkSessionStatus.PLANNED) { "不自动恢复已结束或取消的安排，请另行创建" }
        val current = requireNotNull(repository.findById(original.id)) { "计划时段不存在或不属于本人" }
        require(current == original) { "计划时段已变更，请刷新" }
        if (current.status == status) return@run current
        require(current.status == WorkSessionStatus.PLANNED) { "只能结束计划中的时段" }
        val saved = current.copy(status = status)
        check(repository.update(saved)) { "计划时段保存失败" }
        saved
    }
}

class DeleteWorkSessionUseCase(private val repository: WorkSessionRepository, private val transaction: SchedulingTransaction) {
    suspend operator fun invoke(original: WorkSession): Boolean = transaction.run {
        val current = repository.findById(original.id) ?: return@run false
        require(current == original) { "计划时段已变更，请刷新后重新确认删除" }
        repository.deleteById(current.id)
    }
}

data class PersonalSchedule(val events: List<FixedEvent>, val workSessions: List<WorkSession>)

/** 固定事件和计划时段使用同一时间范围；取消的安排不再占用日历。历史完成项保留。 */
class GetPersonalScheduleUseCase(private val repository: WorkSessionRepository, private val transaction: SchedulingTransaction) {
    suspend operator fun invoke(start: Long, end: Long): PersonalSchedule = transaction.run {
        SchedulingInput.requireRange(start, end)
        PersonalSchedule(repository.listFixedEventsInRange(start, end), repository.listInRange(start, end).filter { it.status != WorkSessionStatus.CANCELLED })
    }
}
