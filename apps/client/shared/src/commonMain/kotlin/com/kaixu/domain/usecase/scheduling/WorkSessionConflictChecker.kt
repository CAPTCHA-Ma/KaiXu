package com.kaixu.domain.usecase.scheduling

import com.kaixu.core.platform.LocalZone
import com.kaixu.domain.model.scheduling.Recurrence
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.scheduling.WorkSessionConfirmation
import com.kaixu.domain.model.scheduling.WorkSessionConflictItem
import com.kaixu.domain.model.scheduling.WorkSessionConflictKind
import com.kaixu.domain.model.scheduling.WorkSessionDeadlineWarning
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.tasks.TaskDeadline
import com.kaixu.domain.model.tasks.deadline
import com.kaixu.domain.repository.WorkSessionWriteScope
import com.kaixu.domain.validation.scheduling.SchedulingInput

/**
 * 在写事务内对候选个人工作时段执行统一冲突与超截止审查：
 * 1. 使用 [Recurrence.expand] 按 [zone] 展开本人固定事件（含每周重复、跨零点回溯、改期例外与已取消占位，排除他人共享只读事件）；
 * 2. 检查本人非取消个人工作时段重叠（排除 [excludeIds]），回填关联任务标题；
 * 3. 检查批量候选时段内部两两重叠；
 * 4. 按 [TaskDeadline] 与 [zone] 判定截止超期警告；
 * 5. 生成绑定草稿、任务截止与冲突快照的稳定 [WorkSessionConfirmation.confirmationKey]。
 */
internal object WorkSessionConflictChecker {
    suspend fun review(
        scope: WorkSessionWriteScope,
        proposed: List<WorkSession>,
        excludeIds: Set<String>,
        targetKey: String,
        zone: LocalZone,
    ): WorkSessionConfirmation {
        if (proposed.isEmpty()) {
            return WorkSessionConfirmation(
                confirmationKey = "$targetKey|${zone.id}|empty",
                proposed = emptyList(),
                conflicts = emptyList(),
                deadlineWarnings = emptyList(),
            )
        }

        val tasksById = linkedMapOf<String, Task>()
        for (taskId in proposed.mapNotNull { it.taskId }.distinct().sorted()) {
            val (task, _) = scope.requirePlannableTask(taskId)
            tasksById[taskId] = task
        }

        val minStart = proposed.minOf { it.startsAt }
        val maxEnd = proposed.maxOf { it.endsAt }

        val ownerEvents = scope.listAllFixedEvents().filter { it.createdBy == scope.ownerUserId }
        val overrides = scope.listFixedEventOverridesIncludingDeleted()
        val expandedOccurrences = Recurrence.expand(
            events = ownerEvents,
            overridesIncludingCancelled = overrides,
            windowStart = minStart,
            windowEnd = maxEnd,
            zone = zone,
        ).filter { it.createdBy == scope.ownerUserId }

        val eventConflicts = expandedOccurrences.mapNotNull { occurrence ->
            val matchedIds = proposed
                .filter { SchedulingInput.intervalsOverlap(it.startsAt, it.endsAt, occurrence.startsAt, occurrence.endsAt) }
                .map { it.id }
            if (matchedIds.isEmpty()) {
                null
            } else {
                WorkSessionConflictItem(
                    kind = WorkSessionConflictKind.FIXED_EVENT,
                    key = occurrence.key,
                    recordId = occurrence.eventId,
                    title = occurrence.title,
                    startsAt = occurrence.startsAt,
                    endsAt = occurrence.endsAt,
                    isRecurringOccurrence = occurrence.isRecurring,
                    proposedSessionIds = matchedIds,
                )
            }
        }

        val extraTaskTitleCache = hashMapOf<String, String?>()
        suspend fun resolveTaskTitle(taskId: String?): String {
            if (taskId == null) return "我的工作时段"
            tasksById[taskId]?.let { return it.title }
            val cached = if (taskId in extraTaskTitleCache) {
                extraTaskTitleCache[taskId]
            } else {
                scope.findTaskById(taskId)?.title.also { extraTaskTitleCache[taskId] = it }
            }
            return cached ?: "我的工作时段"
        }

        val existingSessionConflicts = scope.listInRange(minStart, maxEnd)
            .filter {
                it.ownerUserId == scope.ownerUserId &&
                    it.id !in excludeIds &&
                    it.status != WorkSessionStatus.CANCELLED
            }
            .mapNotNull { existing ->
                val matchedIds = proposed
                    .filter { SchedulingInput.intervalsOverlap(it.startsAt, it.endsAt, existing.startsAt, existing.endsAt) }
                    .map { it.id }
                if (matchedIds.isEmpty()) {
                    null
                } else {
                    WorkSessionConflictItem(
                        kind = WorkSessionConflictKind.WORK_SESSION,
                        key = existing.id,
                        recordId = existing.id,
                        title = resolveTaskTitle(existing.taskId),
                        startsAt = existing.startsAt,
                        endsAt = existing.endsAt,
                        isRecurringOccurrence = false,
                        proposedSessionIds = matchedIds,
                    )
                }
            }

        val proposedOverlapConflicts = buildList {
            for (i in proposed.indices) {
                for (j in i + 1 until proposed.size) {
                    val first = proposed[i]
                    val second = proposed[j]
                    if (SchedulingInput.intervalsOverlap(first.startsAt, first.endsAt, second.startsAt, second.endsAt)) {
                        add(
                            WorkSessionConflictItem(
                                kind = WorkSessionConflictKind.PROPOSED_OVERLAP,
                                key = "${first.id}:${second.id}",
                                recordId = second.id,
                                title = resolveTaskTitle(second.taskId),
                                startsAt = maxOf(first.startsAt, second.startsAt),
                                endsAt = minOf(first.endsAt, second.endsAt),
                                isRecurringOccurrence = false,
                                proposedSessionIds = listOf(first.id, second.id),
                            ),
                        )
                    }
                }
            }
        }

        val conflicts = (eventConflicts + existingSessionConflicts + proposedOverlapConflicts)
            .distinctBy { it.kind to it.key }
            .sortedWith(compareBy({ it.startsAt }, { it.kind.ordinal }, { it.key }))

        val deadlineWarnings = proposed.mapNotNull { session ->
            val taskId = session.taskId ?: return@mapNotNull null
            val task = tasksById.getValue(taskId)
            val deadline = task.deadline
            if (SchedulingInput.exceedsDeadline(session.startsAt, session.endsAt, deadline, zone)) {
                WorkSessionDeadlineWarning(
                    sessionId = session.id,
                    taskId = task.id,
                    taskTitle = task.title,
                    deadline = deadline,
                    sessionEndsAt = session.endsAt,
                )
            } else {
                null
            }
        }

        val key = buildConfirmationKey(
            targetKey = targetKey,
            zoneId = zone.id,
            proposed = proposed,
            tasksById = tasksById,
            conflicts = conflicts,
            deadlineWarnings = deadlineWarnings,
        )
        return WorkSessionConfirmation(
            confirmationKey = key,
            proposed = proposed,
            conflicts = conflicts,
            deadlineWarnings = deadlineWarnings,
        )
    }

    fun accepted(review: WorkSessionConfirmation): List<WorkSession> = review.proposed.map { candidate ->
        candidate.copy(
            overlapConfirmed = review.conflicts.any { candidate.id in it.proposedSessionIds },
        )
    }

    private fun buildConfirmationKey(
        targetKey: String,
        zoneId: String,
        proposed: List<WorkSession>,
        tasksById: Map<String, Task>,
        conflicts: List<WorkSessionConflictItem>,
        deadlineWarnings: List<WorkSessionDeadlineWarning>,
    ): String {
        val proposedPart = proposed.joinToString(";") { s ->
            listOf(
                s.id,
                s.taskId.orEmpty(),
                s.startsAt.toString(),
                s.endsAt.toString(),
                s.note.orEmpty(),
                s.isLocked.toString(),
                s.focusTargetSeconds?.toString().orEmpty(),
                s.stageId.orEmpty(),
            ).joinToString(",")
        }
        val taskPart = tasksById.entries
            .sortedBy { it.key }
            .joinToString(";") { (id, task) -> "$id:${encodeDeadline(task.deadline)}" }
        val conflictPart = conflicts.joinToString(";") { c ->
            "${c.kind.name}:${c.key}:${c.startsAt}-${c.endsAt}:${c.proposedSessionIds.joinToString(",")}"
        }
        val deadlinePart = deadlineWarnings.joinToString(";") { w ->
            "${w.sessionId}:${w.taskId}:${encodeDeadline(w.deadline)}:${w.sessionEndsAt}"
        }
        return "$targetKey|$zoneId##$proposedPart##$taskPart##$conflictPart##$deadlinePart"
    }

    private fun encodeDeadline(deadline: TaskDeadline): String = when (deadline) {
        TaskDeadline.None -> "none"
        is TaskDeadline.DateOnly -> "date:${deadline.date}"
        is TaskDeadline.ExactTime -> "at:${deadline.epochMillis}"
    }
}
