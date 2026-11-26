package com.kaixu.domain.usecase.scheduling

import com.kaixu.core.platform.CalendarDate
import com.kaixu.core.platform.LocalZone
import com.kaixu.domain.model.scheduling.EditScope
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.FixedEventDraft
import com.kaixu.domain.model.scheduling.FixedEventTarget
import com.kaixu.domain.model.scheduling.Recurrence
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.repository.FixedEventRepository
import com.kaixu.domain.repository.FixedEventWriteScope
import com.kaixu.domain.validation.scheduling.FixedEventForm

/** 创建固定事件：校验表单及本人进行中工作区，展开课次检查自身事件与个人时段冲突并支持确认保留重叠后保存。 */
class CreateFixedEventCommandUseCase(
    private val repository: FixedEventRepository,
    private val defaultZone: LocalZone = LocalZone.systemDefault(),
) {
    suspend operator fun invoke(
        draft: FixedEventDraft,
        confirmed: FixedEventConfirmation? = null,
        zone: LocalZone = defaultZone,
    ): FixedEventSaveResult {
        val normalized = FixedEventForm.normalizedDraft(draft, zone)
        return repository.writeTransaction {
            requireActiveTargetWorkspace(normalized.workspaceId)
            val ranges = Recurrence.proposedRanges(normalized, zone)
            val conflicts = findOwnerConflicts(ranges = ranges, zone = zone)
            val key = buildConfirmationKey(normalized, targetKey = "create", scope = null, conflicts = conflicts)
            if (conflicts.isNotEmpty() && confirmed?.key != key) {
                return@writeTransaction FixedEventSaveResult.NeedsConfirmation(FixedEventConfirmation(key, conflicts))
            }
            val saved = insertEvent(
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
    }
}

/**
 * 编辑固定事件：支持单次/整条序列修改，以及重复序列中某次课次按“仅本次”或“本次及后续”范围编辑；
 * 若按记录 ID 直接打开例外课次，亦自动识别所属序列以保留作用域编辑能力。
 */
class UpdateFixedEventCommandUseCase(
    private val repository: FixedEventRepository,
    private val defaultZone: LocalZone = LocalZone.systemDefault(),
) {
    suspend operator fun invoke(
        target: FixedEventTarget,
        draft: FixedEventDraft,
        scope: EditScope = EditScope.THIS_OCCURRENCE,
        confirmed: FixedEventConfirmation? = null,
        zone: LocalZone = defaultZone,
    ): FixedEventSaveResult = repository.writeTransaction {
        when (target) {
            is FixedEventTarget.SingleOrSeries -> {
                val current = requireNotNull(findEventById(target.eventId)) { "事件已不可用，请刷新后重试。" }
                if (current.seriesId != null && current.originalDate != null) {
                    updateOccurrence(
                        seriesId = current.seriesId,
                        originalDate = current.originalDate,
                        draft = draft,
                        scope = scope,
                        confirmed = confirmed,
                        zone = zone,
                    )
                } else {
                    updateSingleOrSeries(current, draft, confirmed, zone)
                }
            }
            is FixedEventTarget.Occurrence -> updateOccurrence(
                seriesId = target.seriesId,
                originalDate = target.originalDate,
                draft = draft,
                scope = scope,
                confirmed = confirmed,
                zone = zone,
            )
        }
    }

    private suspend fun FixedEventWriteScope.updateSingleOrSeries(
        current: FixedEvent,
        draft: FixedEventDraft,
        confirmed: FixedEventConfirmation?,
        zone: LocalZone,
    ): FixedEventSaveResult {
        requireEditableEvent(current)
        val normalized = FixedEventForm.normalizedDraft(draft, zone)
        if (normalized.workspaceId != current.workspaceId || normalized.workspaceId != null) {
            requireActiveTargetWorkspace(normalized.workspaceId)
        }
        val ranges = Recurrence.proposedRanges(normalized, zone)
        val conflicts = findOwnerConflicts(
            ranges = ranges,
            excludeEventIds = setOf(current.id),
            excludeSeriesId = if (current.isRecurring) current.id else null,
            zone = zone,
        )
        val key = buildConfirmationKey(normalized, targetKey = "event:${current.id}", scope = null, conflicts = conflicts)
        if (conflicts.isNotEmpty() && confirmed?.key != key) {
            return FixedEventSaveResult.NeedsConfirmation(FixedEventConfirmation(key, conflicts))
        }
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
        check(replaceEvent(proposed)) { "事件已不可用，请刷新后重试。" }
        return FixedEventSaveResult.Saved(proposed)
    }

    private suspend fun FixedEventWriteScope.updateOccurrence(
        seriesId: String,
        originalDate: String,
        draft: FixedEventDraft,
        scope: EditScope,
        confirmed: FixedEventConfirmation?,
        zone: LocalZone,
    ): FixedEventSaveResult {
        val series = requireNotNull(findEventById(seriesId)) { "事件已不可用，请刷新后重试。" }
        requireEditableEvent(series)
        val repeatWeekdays = series.repeatWeekdays
        val repeatUntil = series.repeatUntil
        require(series.seriesId == null && repeatWeekdays != null && repeatUntil != null) {
            "目标记录不是有效的重复事件序列。"
        }
        val seriesFirstDate = zone.localDateOf(series.startsAt)
        check(
            CalendarDate.compareDates(originalDate, seriesFirstDate) >= 0 &&
                CalendarDate.compareDates(originalDate, repeatUntil) <= 0 &&
                CalendarDate.isoWeekday(originalDate) in repeatWeekdays,
        ) { "该课次已不在重复范围内，请刷新日程。" }

        val overrides = listOverridesIncludingDeleted()
        val existingOverride = overrides.firstOrNull {
            it.seriesId == series.id && it.originalDate == originalDate
        }
        if (existingOverride?.isCancelled == true) {
            throw IllegalStateException("该课次已取消，无法继续编辑，请刷新日程。")
        }
        if (existingOverride != null) {
            requireEditableEvent(existingOverride)
        }

        return when (scope) {
            EditScope.THIS_OCCURRENCE -> {
                val effectiveDraft = FixedEventForm.normalizedDraft(
                    draft.copy(repeatWeekdays = null, repeatUntil = null),
                    zone,
                )
                requireActiveTargetWorkspace(effectiveDraft.workspaceId)
                val ranges = Recurrence.proposedRanges(effectiveDraft, zone)
                val excludeIds = existingOverride?.let { setOf(it.id) }.orEmpty()
                val conflicts = findOwnerConflicts(
                    ranges = ranges,
                    excludeEventIds = excludeIds,
                    excludeOccurrenceKey = series.id to originalDate,
                    zone = zone,
                )
                val key = buildConfirmationKey(
                    effectiveDraft,
                    targetKey = "occ:${series.id}@$originalDate",
                    scope = EditScope.THIS_OCCURRENCE,
                    conflicts = conflicts,
                )
                if (conflicts.isNotEmpty() && confirmed?.key != key) {
                    return FixedEventSaveResult.NeedsConfirmation(FixedEventConfirmation(key, conflicts))
                }
                val override = FixedEvent(
                    id = existingOverride?.id ?: newSchedulingId(),
                    title = effectiveDraft.title,
                    startsAt = effectiveDraft.startsAt,
                    endsAt = effectiveDraft.endsAt,
                    description = effectiveDraft.description,
                    workspaceId = effectiveDraft.workspaceId,
                    createdBy = ownerUserId,
                    reminderOffsetMinutes = effectiveDraft.reminderOffsetMinutes,
                    seriesId = series.id,
                    originalDate = originalDate,
                    isOverride = true,
                )
                val saved = if (existingOverride == null) {
                    insertEvent(override)
                } else {
                    check(replaceEvent(override)) { "课次已不可用，请刷新后重试。" }
                    override
                }
                FixedEventSaveResult.Saved(saved)
            }
            EditScope.THIS_AND_FUTURE -> {
                val mergedDraft = if (draft.repeatWeekdays == null && draft.repeatUntil == null) {
                    draft.copy(
                        repeatWeekdays = repeatWeekdays,
                        repeatUntil = repeatUntil,
                    )
                } else {
                    draft
                }
                val normalized = FixedEventForm.normalizedDraft(mergedDraft, zone)
                requireActiveTargetWorkspace(normalized.workspaceId)
                val ranges = Recurrence.proposedRanges(normalized, zone)
                val conflicts = findOwnerConflicts(
                    ranges = ranges,
                    excludeSeriesFromDate = series.id to originalDate,
                    zone = zone,
                )
                val key = buildConfirmationKey(
                    normalized,
                    targetKey = "occ:${series.id}@$originalDate",
                    scope = EditScope.THIS_AND_FUTURE,
                    conflicts = conflicts,
                )
                if (conflicts.isNotEmpty() && confirmed?.key != key) {
                    return FixedEventSaveResult.NeedsConfirmation(FixedEventConfirmation(key, conflicts))
                }

                // 清理本次及之后原有的活动例外，避免旧例外残留在界面或后续索引冲突中。
                overrides.filter {
                    !it.isCancelled &&
                        it.seriesId == series.id &&
                        it.originalDate != null &&
                        CalendarDate.compareDates(it.originalDate, originalDate) >= 0
                }.forEach { removeEventById(it.id) }

                val dayBefore = CalendarDate.plusDays(originalDate, -1L)
                if (CalendarDate.compareDates(dayBefore, seriesFirstDate) < 0) {
                    check(removeEventById(series.id)) { "序列已不可用，请刷新后重试。" }
                } else {
                    check(truncateSeriesUntil(series.id, dayBefore)) { "序列已不可用，请刷新后重试。" }
                }
                val nextSeries = insertEvent(
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
                FixedEventSaveResult.Saved(nextSeries)
            }
        }
    }
}

/** 删除固定事件：支持删除单次/整条序列，或按“仅本次”“本次及后续”幂等取消或截断重复课次。 */
class DeleteFixedEventCommandUseCase(
    private val repository: FixedEventRepository,
    private val defaultZone: LocalZone = LocalZone.systemDefault(),
) {
    suspend operator fun invoke(
        target: FixedEventTarget,
        scope: EditScope = EditScope.THIS_OCCURRENCE,
        zone: LocalZone = defaultZone,
    ): Boolean = repository.writeTransaction {
        when (target) {
            is FixedEventTarget.SingleOrSeries -> {
                val current = findEventById(target.eventId) ?: return@writeTransaction false
                if (current.seriesId != null && current.originalDate != null) {
                    deleteOccurrence(current.seriesId, current.originalDate, scope, zone)
                } else {
                    requireEditableEvent(current)
                    if (current.isRecurring) {
                        listOverridesIncludingDeleted()
                            .filter { it.seriesId == current.id && !it.isCancelled }
                            .forEach { removeEventById(it.id) }
                    }
                    removeEventById(current.id)
                }
            }
            is FixedEventTarget.Occurrence ->
                deleteOccurrence(target.seriesId, target.originalDate, scope, zone)
        }
    }

    private suspend fun FixedEventWriteScope.deleteOccurrence(
        seriesId: String,
        originalDate: String,
        scope: EditScope,
        zone: LocalZone,
    ): Boolean {
        val series = findEventById(seriesId) ?: return false
        requireEditableEvent(series)
        val repeatWeekdays = series.repeatWeekdays ?: return false
        val repeatUntil = series.repeatUntil ?: return false
        if (series.seriesId != null) return false

        val seriesFirstDate = zone.localDateOf(series.startsAt)
        val overrides = listOverridesIncludingDeleted()

        return when (scope) {
            EditScope.THIS_OCCURRENCE -> {
                val existing = overrides.firstOrNull {
                    it.seriesId == series.id && it.originalDate == originalDate
                }
                if (existing != null) {
                    if (existing.isCancelled) return false
                    requireEditableEvent(existing)
                    return removeEventById(existing.id)
                }
                if (CalendarDate.compareDates(originalDate, seriesFirstDate) < 0 ||
                    CalendarDate.compareDates(originalDate, repeatUntil) > 0 ||
                    CalendarDate.isoWeekday(originalDate) !in repeatWeekdays
                ) {
                    return false
                }
                val start = zone.atLocalDateTimeMillis(originalDate, zone.localTimeOfDayMillis(series.startsAt))
                val duration = series.endsAt - series.startsAt
                val tombstone = insertEvent(
                    FixedEvent(
                        id = newSchedulingId(),
                        title = series.title,
                        startsAt = start,
                        endsAt = start + duration,
                        createdBy = ownerUserId,
                        seriesId = series.id,
                        originalDate = originalDate,
                        isOverride = true,
                    ),
                )
                removeEventById(tombstone.id)
            }
            EditScope.THIS_AND_FUTURE -> {
                overrides.filter {
                    !it.isCancelled &&
                        it.seriesId == series.id &&
                        it.originalDate != null &&
                        CalendarDate.compareDates(it.originalDate, originalDate) >= 0
                }.forEach { removeEventById(it.id) }

                val dayBefore = CalendarDate.plusDays(originalDate, -1L)
                if (CalendarDate.compareDates(dayBefore, seriesFirstDate) < 0) {
                    removeEventById(series.id)
                } else {
                    truncateSeriesUntil(series.id, dayBefore)
                }
            }
        }
    }
}

private suspend fun FixedEventWriteScope.findOwnerConflicts(
    ranges: List<Pair<Long, Long>>,
    excludeEventIds: Set<String> = emptySet(),
    excludeOccurrenceKey: Pair<String, String>? = null,
    excludeSeriesId: String? = null,
    excludeSeriesFromDate: Pair<String, String>? = null,
    zone: LocalZone,
): List<ScheduleConflict> {
    if (ranges.isEmpty()) return emptyList()
    val minStart = ranges.minOf { it.first }
    val maxEnd = ranges.maxOf { it.second }
    val ownerEvents = listAllEvents().filter { it.createdBy == ownerUserId }
    val overrides = listOverridesIncludingDeleted()
    val occurrences = Recurrence.expand(ownerEvents, overrides, minStart, maxEnd, zone)

    val eventConflicts = occurrences
        .filter { it.createdBy == ownerUserId }
        .filter { occurrence ->
            occurrence.eventId !in excludeEventIds &&
                (excludeSeriesId == null || occurrence.seriesId != excludeSeriesId) &&
                (
                    excludeOccurrenceKey == null ||
                        occurrence.seriesId != excludeOccurrenceKey.first ||
                        occurrence.originalDate != excludeOccurrenceKey.second
                    ) &&
                (
                    excludeSeriesFromDate == null ||
                        occurrence.seriesId != excludeSeriesFromDate.first ||
                        occurrence.originalDate == null ||
                        CalendarDate.compareDates(occurrence.originalDate, excludeSeriesFromDate.second) < 0
                    ) &&
                ranges.any { (start, end) -> intervalsOverlap(start, end, occurrence.startsAt, occurrence.endsAt) }
        }
        .map {
            ScheduleConflict(ScheduleConflictKind.EVENT, it.key, it.title, it.startsAt, it.endsAt)
        }

    val taskTitleCache = hashMapOf<String, String?>()
    suspend fun resolveSessionTitle(taskId: String?): String {
        if (taskId == null) return "我的工作时段"
        val title = if (taskId in taskTitleCache) {
            taskTitleCache[taskId]
        } else {
            findTaskById(taskId)?.title.also { taskTitleCache[taskId] = it }
        }
        return title ?: "我的工作时段"
    }

    val sessionConflicts = listOwnerWorkSessionsInRange(minStart, maxEnd)
        .filter { it.ownerUserId == ownerUserId && it.status != WorkSessionStatus.CANCELLED }
        .filter { session ->
            ranges.any { (start, end) -> intervalsOverlap(start, end, session.startsAt, session.endsAt) }
        }
        .map { session ->
            ScheduleConflict(
                kind = ScheduleConflictKind.WORK_SESSION,
                id = session.id,
                title = resolveSessionTitle(session.taskId),
                startsAt = session.startsAt,
                endsAt = session.endsAt,
            )
        }

    return (eventConflicts + sessionConflicts)
        .distinctBy { it.kind to it.id }
        .sortedWith(compareBy({ it.startsAt }, { it.kind.ordinal }, { it.id }))
}

private fun intervalsOverlap(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Boolean =
    aStart < bEnd && bStart < aEnd

private fun buildConfirmationKey(
    draft: FixedEventDraft,
    targetKey: String,
    scope: EditScope?,
    conflicts: List<ScheduleConflict>,
): String {
    val draftPart = listOf(
        draft.title,
        draft.description.orEmpty(),
        draft.startsAt.toString(),
        draft.endsAt.toString(),
        draft.workspaceId.orEmpty(),
        draft.repeatWeekdays?.sorted()?.joinToString(",").orEmpty(),
        draft.repeatUntil.orEmpty(),
        draft.reminderOffsetMinutes?.toString().orEmpty(),
        targetKey,
        scope?.name.orEmpty(),
    ).joinToString("|")
    val conflictPart = conflicts.joinToString(";") {
        "${it.kind.name}:${it.id}:${it.startsAt}-${it.endsAt}"
    }
    return "$draftPart##$conflictPart"
}
