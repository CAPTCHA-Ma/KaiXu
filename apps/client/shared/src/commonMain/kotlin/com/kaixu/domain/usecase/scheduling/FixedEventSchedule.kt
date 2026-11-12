package com.kaixu.domain.usecase.scheduling

import com.kaixu.data.datasource.FixedEventDataSource
import com.kaixu.data.datasource.WorkSessionDataSource
import com.kaixu.data.datasource.WorkspaceDataSource
import com.kaixu.domain.model.scheduling.EditScope
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.FixedEventDraft
import com.kaixu.domain.model.scheduling.Recurrence
import com.kaixu.domain.model.scheduling.ScheduleOccurrence
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.repository.SchedulingTransaction
import com.kaixu.domain.validation.scheduling.FixedEventForm
import com.kaixu.domain.validation.scheduling.ShanghaiTime

enum class ScheduleConflictKind { EVENT, WORK_SESSION }

/** 保存前发现的本人日程重叠项。 */
data class ScheduleConflict(
    val kind: ScheduleConflictKind,
    val id: String,
    val title: String,
    val startsAt: Long,
    val endsAt: Long,
)

/** 用户确认绑定当次输入；表单或范围变化后必须重新确认。 */
data class FixedEventConfirmation(
    val key: String,
    val conflicts: List<ScheduleConflict>,
)

sealed interface FixedEventSaveResult {
    data class Saved(val event: FixedEvent) : FixedEventSaveResult
    data class NeedsConfirmation(val confirmation: FixedEventConfirmation) : FixedEventSaveResult
}

/**
 * 固定事件的写入口：创建、编辑、删除，含工作区归属、冲突确认与每周重复范围。
 * 所有读取和写入都在同一事务中；需要用户确认时先结束事务并返回快照，
 * 不跨越用户交互持有数据库锁。
 */
class FixedEventSchedule(
    private val events: FixedEventDataSource,
    private val sessions: WorkSessionDataSource,
    private val workspaces: WorkspaceDataSource,
    private val ownerUserId: String,
    private val transaction: SchedulingTransaction,
) {
    suspend fun create(
        draft: FixedEventDraft,
        confirmed: FixedEventConfirmation? = null,
    ): FixedEventSaveResult = transaction.run {
        val normalized = normalize(draft)
        requireWorkspace(normalized.workspaceId)
        val ranges = Recurrence.proposedRanges(normalized)
        val conflicts = findConflicts(ranges, excludeEventIds = emptySet(), excludeSeriesId = null)
        val key = confirmationKey(normalized, originalDate = null, scope = null)
        if (conflicts.isNotEmpty() && confirmed?.key != key) {
            return@run FixedEventSaveResult.NeedsConfirmation(FixedEventConfirmation(key, conflicts))
        }
        val saved = events.createEvent(
            FixedEvent(
                id = newSchedulingId(),
                title = normalized.title,
                startsAt = normalized.startsAt,
                endsAt = normalized.endsAt,
                description = normalized.description,
                workspaceId = normalized.workspaceId,
                createdBy = ownerUserId,
                reminderOffsetMinutes = normalized.reminderOffsetMinutes,
                repeatWeekdays = normalized.repeatWeekdays,
                repeatUntil = normalized.repeatUntil,
            ),
        )
        FixedEventSaveResult.Saved(saved)
    }

    /**
     * 编辑事件。
     * [originalDate] 为空表示编辑单次事件或整条序列行；非空表示编辑重复序列中的某次课次，
     * 此时 [scope] 决定只改本次还是截断并新建后续序列。
     */
    suspend fun update(
        target: FixedEvent,
        draft: FixedEventDraft,
        originalDate: String? = null,
        scope: EditScope = EditScope.THIS_OCCURRENCE,
        confirmed: FixedEventConfirmation? = null,
    ): FixedEventSaveResult = transaction.run {
        val current = requireNotNull(events.findEventById(target.id)) { "事件已不可用，请刷新后重试。" }
        require(current.createdBy == ownerUserId) { "只能编辑本人的事件。" }
        val normalized = normalize(draft)
        requireWorkspace(normalized.workspaceId)
        val ranges = Recurrence.proposedRanges(normalized)
        val excludeIds = mutableSetOf(current.id)
        val existingOverride = if (originalDate != null) {
            events.listOverridesIncludingDeleted()
                .firstOrNull { it.seriesId == current.id && it.originalDate == originalDate }
                ?.also { if (!it.isCancelled) excludeIds += it.id }
        } else null
        val excludeSeriesId = if (originalDate != null) current.id else null
        val conflicts = findConflicts(ranges, excludeIds, excludeSeriesId)
        val key = confirmationKey(normalized, originalDate, scope)
        if (conflicts.isNotEmpty() && confirmed?.key != key) {
            return@run FixedEventSaveResult.NeedsConfirmation(FixedEventConfirmation(key, conflicts))
        }

        when {
            originalDate == null -> {
                val proposed = current.copy(
                    title = normalized.title,
                    description = normalized.description,
                    startsAt = normalized.startsAt,
                    endsAt = normalized.endsAt,
                    workspaceId = normalized.workspaceId,
                    reminderOffsetMinutes = normalized.reminderOffsetMinutes,
                    repeatWeekdays = normalized.repeatWeekdays,
                    repeatUntil = normalized.repeatUntil,
                )
                check(events.updateEvent(proposed)) { "事件已不可用，请刷新后重试。" }
                FixedEventSaveResult.Saved(proposed)
            }
            scope == EditScope.THIS_OCCURRENCE -> {
                // 已取消的课次占位不能复用，重新建立例外行。
                val reusable = existingOverride?.takeUnless { it.isCancelled }
                val override = FixedEvent(
                    id = reusable?.id ?: newSchedulingId(),
                    title = normalized.title,
                    startsAt = normalized.startsAt,
                    endsAt = normalized.endsAt,
                    description = normalized.description,
                    workspaceId = normalized.workspaceId,
                    createdBy = ownerUserId,
                    reminderOffsetMinutes = normalized.reminderOffsetMinutes,
                    seriesId = current.id,
                    originalDate = originalDate,
                    isOverride = true,
                )
                val saved = if (reusable == null) {
                    events.createEvent(override)
                } else {
                    check(events.updateEvent(override)) { "课次已不可用，请刷新后重试。" }
                    override
                }
                FixedEventSaveResult.Saved(saved)
            }
            else -> {
                // 截断旧序列，再以所选课次为新起点建立后续序列。
                check(events.truncateSeriesUntil(current.id, ShanghaiTime.plusDays(originalDate, -1))) {
                    "序列已不可用，请刷新后重试。"
                }
                val next = events.createEvent(
                    FixedEvent(
                        id = newSchedulingId(),
                        title = normalized.title,
                        startsAt = normalized.startsAt,
                        endsAt = normalized.endsAt,
                        description = normalized.description,
                        workspaceId = normalized.workspaceId,
                        createdBy = ownerUserId,
                        reminderOffsetMinutes = normalized.reminderOffsetMinutes,
                        repeatWeekdays = normalized.repeatWeekdays ?: current.repeatWeekdays,
                        repeatUntil = normalized.repeatUntil ?: current.repeatUntil,
                    ),
                )
                FixedEventSaveResult.Saved(next)
            }
        }
    }

    /**
     * 删除事件。[originalDate] 为空表示删除整条记录（序列会连带隐藏其课次）；
     * 非空时按 [scope] 取消本次或截断后续。
     */
    suspend fun delete(
        target: FixedEvent,
        originalDate: String? = null,
        scope: EditScope = EditScope.THIS_OCCURRENCE,
    ): Boolean = transaction.run {
        val current = events.findEventById(target.id) ?: return@run false
        require(current.createdBy == ownerUserId) { "只能删除本人的事件。" }
        when {
            originalDate == null -> {
                if (current.repeatWeekdays != null) {
                    events.listOverridesIncludingDeleted()
                        .filter { it.seriesId == current.id && !it.isCancelled }
                        .forEach { events.deleteEventById(it.id) }
                }
                events.deleteEventById(current.id)
            }
            scope == EditScope.THIS_OCCURRENCE -> cancelOccurrence(current, originalDate)
            else -> {
                val dayBefore = ShanghaiTime.plusDays(originalDate, -1)
                if (ShanghaiTime.compareDates(dayBefore, ShanghaiTime.localDateOf(current.startsAt)) < 0) {
                    events.deleteEventById(current.id)
                } else {
                    events.truncateSeriesUntil(current.id, dayBefore)
                }
            }
        }
    }

    /** 日/周视图使用的展开结果；窗口为半开区间。 */
    suspend fun occurrences(windowStart: Long, windowEnd: Long): List<ScheduleOccurrence> {
        require(windowEnd > windowStart) { "结束时间必须晚于开始时间。" }
        val all = events.listEvents().filter { it.createdBy == ownerUserId }
        val overrides = events.listOverridesIncludingDeleted()
        return Recurrence.expand(all, overrides, windowStart, windowEnd)
    }

    private suspend fun cancelOccurrence(current: FixedEvent, originalDate: String): Boolean {
        val existing = events.listOverridesIncludingDeleted()
            .firstOrNull { it.seriesId == current.id && it.originalDate == originalDate }
        if (existing != null) {
            return if (existing.isCancelled) false else events.deleteEventById(existing.id)
        }
        val timeOfDay = ShanghaiTime.millisOfDay(current.startsAt)
        val duration = current.endsAt - current.startsAt
        val start = ShanghaiTime.atLocalDate(originalDate, timeOfDay)
        val tombstone = events.createEvent(
            FixedEvent(
                id = newSchedulingId(),
                title = current.title,
                startsAt = start,
                endsAt = start + duration,
                createdBy = ownerUserId,
                seriesId = current.id,
                originalDate = originalDate,
                isOverride = true,
            ),
        )
        return events.deleteEventById(tombstone.id)
    }

    private suspend fun findConflicts(
        ranges: List<Pair<Long, Long>>,
        excludeEventIds: Set<String>,
        excludeSeriesId: String?,
    ): List<ScheduleConflict> {
        if (ranges.isEmpty()) return emptyList()
        val minStart = ranges.minOf { it.first }
        val maxEnd = ranges.maxOf { it.second }
        val all = events.listEvents()
        val overrides = events.listOverridesIncludingDeleted()
        val occurrences = Recurrence.expand(all, overrides, minStart, maxEnd)
        val eventConflicts = occurrences
            .filter { it.createdBy == ownerUserId }
            .filter { occurrence ->
                occurrence.eventId !in excludeEventIds &&
                    (excludeSeriesId == null || occurrence.seriesId != excludeSeriesId) &&
                    ranges.any { (start, end) -> overlaps(start, end, occurrence.startsAt, occurrence.endsAt) }
            }
            .map {
                ScheduleConflict(ScheduleConflictKind.EVENT, it.key, it.title, it.startsAt, it.endsAt)
            }
        val sessionConflicts = sessions.listWorkSessionsInRange(ownerUserId, minStart, maxEnd)
            .filter { it.ownerUserId == ownerUserId && it.status != WorkSessionStatus.CANCELLED }
            .filter { session -> ranges.any { (start, end) -> overlaps(start, end, session.startsAt, session.endsAt) } }
            .map {
                ScheduleConflict(ScheduleConflictKind.WORK_SESSION, it.id, "我的工作时段", it.startsAt, it.endsAt)
            }
        return (eventConflicts + sessionConflicts)
            .distinctBy { it.kind to it.id }
            .sortedWith(compareBy({ it.startsAt }, { it.id }))
    }

    private fun overlaps(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Boolean =
        aStart < bEnd && bStart < aEnd

    private suspend fun requireWorkspace(workspaceId: String?) {
        if (workspaceId == null) return
        val workspace = requireNotNull(workspaces.findWorkspaceById(workspaceId)) { "所选工作区不存在或不可用。" }
        require(workspace.ownerUserId == ownerUserId && workspace.status == WorkspaceStatus.ACTIVE) {
            "只能关联本人进行中的工作区。"
        }
    }

    private fun normalize(draft: FixedEventDraft): FixedEventDraft {
        val normalized = draft.copy(
            title = draft.title.trim(),
            description = draft.description?.trim()?.ifBlank { null },
            repeatWeekdays = draft.repeatWeekdays?.sorted()?.toSet(),
        )
        FixedEventForm.errorOrNull(normalized)?.let { throw IllegalArgumentException(it) }
        return normalized
    }

    private fun confirmationKey(draft: FixedEventDraft, originalDate: String?, scope: EditScope?): String =
        listOf(
            draft.title, draft.startsAt, draft.endsAt, draft.workspaceId,
            draft.repeatWeekdays?.joinToString(","), draft.repeatUntil,
            draft.reminderOffsetMinutes, originalDate, scope,
        ).joinToString("|")
}
