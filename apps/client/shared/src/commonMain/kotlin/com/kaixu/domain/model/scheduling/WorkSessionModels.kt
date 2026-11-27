package com.kaixu.domain.model.scheduling

import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.tasks.TaskDeadline
import com.kaixu.domain.model.workspaces.Workspace

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
internal fun newWorkSessionId(): String = kotlin.uuid.Uuid.random().toString()

/**
 * 新建个人工作时段（支持单条与批量创建）的业务草稿。
 * 不接受所有者、脱链信息或任意状态，避免将持久化对象当作创建命令。
 */
data class WorkSessionDraft(
    val taskId: String,
    val startsAt: Long,
    val endsAt: Long,
    val id: String = newWorkSessionId(),
    val note: String? = null,
    val isLocked: Boolean = false,
    val focusTargetSeconds: Long? = null,
)

/**
 * 编辑既有个人工作时段的业务草稿；时段 ID、所属任务与原 stageId 由目标记录固定保留。
 */
data class WorkSessionEditDraft(
    val startsAt: Long,
    val endsAt: Long,
    val note: String? = null,
    val isLocked: Boolean = false,
    val focusTargetSeconds: Long? = null,
)

/** 面向日历拖拽与边缘拉伸的轻量改期参数，仅变更起止时间，保留说明、锁定与计时目标。 */
data class WorkSessionReschedule(
    val startsAt: Long,
    val endsAt: Long,
)

val WorkSession.isDetached: Boolean
    get() = taskId == null || detachedAt != null

val WorkSession.plannedDurationMillis: Long
    get() = (endsAt - startsAt).coerceAtLeast(0L)

fun WorkSession.toEditDraft(): WorkSessionEditDraft = WorkSessionEditDraft(
    startsAt = startsAt,
    endsAt = endsAt,
    note = note,
    isLocked = isLocked,
    focusTargetSeconds = focusTargetSeconds,
)

fun WorkSession.toDraftOrNull(): WorkSessionDraft? = taskId?.let { linkedTaskId ->
    WorkSessionDraft(
        taskId = linkedTaskId,
        startsAt = startsAt,
        endsAt = endsAt,
        id = id,
        note = note,
        isLocked = isLocked,
        focusTargetSeconds = focusTargetSeconds,
    )
}

/** 批量草稿内部两两重叠对标识。 */
data class ProposedSessionOverlap(
    val firstId: String,
    val secondId: String,
)

/** 个人工作时段保存前的冲突来源分类。 */
enum class WorkSessionConflictKind {
    /** 与本人固定事件（含展开后的每周重复课次或改期例外）重叠。 */
    FIXED_EVENT,
    /** 与本人已保存且未取消的个人工作时段重叠。 */
    WORK_SESSION,
    /** 同批次待创建草稿之间的两两时间重叠。 */
    PROPOSED_OVERLAP,
}

/** 结构化日程冲突项，携带真实展开后的时间区间与关联标题。 */
data class WorkSessionConflictItem(
    val kind: WorkSessionConflictKind,
    /**
     * 冲突项稳定标识：
     * - FIXED_EVENT: ScheduleOccurrence.key（单次为 eventId，重复课次为 "${seriesId}@${originalDate}"）
     * - WORK_SESSION: WorkSession.id
     * - PROPOSED_OVERLAP: "${firstId}:${secondId}"
     */
    val key: String,
    /** 底层记录 ID（FIXED_EVENT 为 occurrence.eventId，WORK_SESSION 为 session.id，PROPOSED_OVERLAP 为 secondId）。 */
    val recordId: String,
    val title: String,
    val startsAt: Long,
    val endsAt: Long,
    val isRecurringOccurrence: Boolean = false,
    /** 与该冲突项发生重叠的待保存时段 ID 列表（按输入顺序去重）。 */
    val proposedSessionIds: List<String> = emptyList(),
)

/** 待保存时段结束时刻晚于关联任务截止要求的超期警告。 */
data class WorkSessionDeadlineWarning(
    val sessionId: String,
    val taskId: String,
    val taskTitle: String,
    val deadline: TaskDeadline,
    val sessionEndsAt: Long,
)

/**
 * 个人工作时段冲突与超截止确认模型。
 * [confirmationKey] 绑定规范化草稿、关联任务截止要求及当次展开后的冲突集合；
 * 草稿、任务截止、固定事件或已有工作时段发生任何变化都会生成新的 key，使旧确认自动失效。
 */
data class WorkSessionConfirmation(
    val confirmationKey: String,
    val proposed: List<WorkSession>,
    val conflicts: List<WorkSessionConflictItem>,
    val deadlineWarnings: List<WorkSessionDeadlineWarning>,
) {
    val hasConflicts: Boolean
        get() = conflicts.isNotEmpty()

    val hasDeadlineWarnings: Boolean
        get() = deadlineWarnings.isNotEmpty()

    val requiresConfirmation: Boolean
        get() = hasConflicts || hasDeadlineWarnings

    val fixedEventConflicts: List<WorkSessionConflictItem>
        get() = conflicts.filter { it.kind == WorkSessionConflictKind.FIXED_EVENT }

    val workSessionConflicts: List<WorkSessionConflictItem>
        get() = conflicts.filter { it.kind == WorkSessionConflictKind.WORK_SESSION }

    val proposedOverlaps: List<ProposedSessionOverlap>
        get() = conflicts
            .filter { it.kind == WorkSessionConflictKind.PROPOSED_OVERLAP && it.proposedSessionIds.size == 2 }
            .map { ProposedSessionOverlap(it.proposedSessionIds[0], it.proposedSessionIds[1]) }

    val pastDeadlineIds: List<String>
        get() = deadlineWarnings.map { it.sessionId }
}

sealed interface WorkSessionSaveResult {
    data class Saved(val sessions: List<WorkSession>) : WorkSessionSaveResult {
        val session: WorkSession
            get() = sessions.single()
    }

    data class NeedsConfirmation(val confirmation: WorkSessionConfirmation) : WorkSessionSaveResult
}

/** 单条个人工作时段的聚合详情与操作能力快照。 */
data class WorkSessionDetail(
    val session: WorkSession,
    val task: Task? = null,
    val workspace: Workspace? = null,
    val workspaceArchived: Boolean = false,
    val isDetached: Boolean = session.isDetached,
    val focusSessions: List<FocusSession> = emptyList(),
    val focusDuration: FocusDuration = FocusDuration(),
    val canEdit: Boolean = false,
    val canReschedule: Boolean = false,
    val canEditFocusTarget: Boolean = false,
    val canComplete: Boolean = false,
    val canCancel: Boolean = false,
    val canDelete: Boolean = false,
    val canStartFocus: Boolean = false,
    val initialDraft: WorkSessionEditDraft? = null,
)

/** 指定任务下单个本人工作时段及其专注统计明细。 */
data class TaskWorkSessionItem(
    val session: WorkSession,
    val focusSessions: List<FocusSession> = emptyList(),
    val focusDuration: FocusDuration = FocusDuration(),
    val canEdit: Boolean = false,
    val canReschedule: Boolean = false,
    val canEditFocusTarget: Boolean = false,
    val canComplete: Boolean = false,
    val canCancel: Boolean = false,
    val canDelete: Boolean = true,
    val canStartFocus: Boolean = false,
) {
    val plannedDurationMillis: Long
        get() = session.plannedDurationMillis
}

/** 针对指定任务的本人工作时段列表与计划/专注统计快照。 */
data class TaskWorkSessionsSnapshot(
    val taskId: String,
    val task: Task? = null,
    val workspace: Workspace? = null,
    val workspaceArchived: Boolean = false,
    val canScheduleMore: Boolean = false,
    val items: List<TaskWorkSessionItem> = emptyList(),
    val plannedCount: Int = 0,
    val completedCount: Int = 0,
    val cancelledCount: Int = 0,
    /** 未取消（PLANNED + COMPLETED）时段累计计划时长（毫秒）。 */
    val totalPlannedMillis: Long = 0L,
    /** 该任务下全部本人时段累计的实际有效专注时长。 */
    val totalFocusDuration: FocusDuration = FocusDuration(),
) {
    val sessions: List<WorkSession>
        get() = items.map { it.session }

    val totalCount: Int
        get() = items.size

    val activeCount: Int
        get() = plannedCount + completedCount
}

/** 指定计划时段下全部本人计时会话与累计有效时长汇总。 */
data class FocusSummary(
    val workSessionId: String,
    val sessions: List<FocusSession>,
    val duration: FocusDuration,
)
