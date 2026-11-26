package com.kaixu.domain.usecase.scheduling

import com.kaixu.core.platform.CalendarDate
import com.kaixu.core.platform.LocalZone
import com.kaixu.domain.model.scheduling.FixedEventDetail
import com.kaixu.domain.model.scheduling.FixedEventTarget
import com.kaixu.domain.model.scheduling.toDraft
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.repository.FixedEventRepository

/** 读取固定事件详情及编辑初始化快照，统一区分单次/序列、重复课次与只读权限。 */
class GetFixedEventDetailUseCase(
    private val repository: FixedEventRepository,
    private val defaultZone: LocalZone = LocalZone.systemDefault(),
) {
    suspend operator fun invoke(
        target: FixedEventTarget,
        zone: LocalZone = defaultZone,
    ): FixedEventDetail? = when (target) {
        is FixedEventTarget.SingleOrSeries -> resolveSingleOrSeries(target.eventId, zone)
        is FixedEventTarget.Occurrence -> resolveOccurrence(target.seriesId, target.originalDate, zone)
    }

    private suspend fun resolveSingleOrSeries(eventId: String, zone: LocalZone): FixedEventDetail? {
        val event = repository.findById(eventId) ?: return null
        if (event.seriesId != null && event.originalDate != null) {
            return resolveOccurrence(event.seriesId, event.originalDate, zone)
        }
        val workspace = event.workspaceId?.let { repository.findWorkspaceById(it) }
        val accessibleIds = workspace?.let { setOf(it.id) }.orEmpty()
        if (!isVisibleFixedEvent(event, repository.ownerUserId, accessibleIds)) return null

        val editable = canEditFixedEvent(event, repository.ownerUserId, workspace)
        return FixedEventDetail(
            target = FixedEventTarget.SingleOrSeries(event.id),
            record = event,
            series = event.takeIf { it.isRecurring },
            occurrenceStart = event.startsAt,
            occurrenceEnd = event.endsAt,
            initialDraft = event.toDraft(),
            workspace = workspace,
            workspaceArchived = workspace?.status == WorkspaceStatus.ARCHIVED,
            isSharedReadOnly = event.createdBy != repository.ownerUserId,
            canEdit = editable,
            canDelete = editable,
            supportsScopeSelection = false,
        )
    }

    private suspend fun resolveOccurrence(
        seriesId: String,
        originalDate: String,
        zone: LocalZone,
    ): FixedEventDetail? {
        val series = repository.findById(seriesId) ?: return null
        val weekdays = series.repeatWeekdays ?: return null
        val repeatUntil = series.repeatUntil ?: return null
        if (series.seriesId != null) return null

        val seriesStartDate = zone.localDateOf(series.startsAt)
        if (CalendarDate.compareDates(originalDate, seriesStartDate) < 0 ||
            CalendarDate.compareDates(originalDate, repeatUntil) > 0
        ) {
            return null
        }
        if (CalendarDate.isoWeekday(originalDate) !in weekdays) return null

        val override = repository.listOverridesIncludingDeleted()
            .firstOrNull { it.seriesId == series.id && it.originalDate == originalDate }
        if (override?.isCancelled == true) return null

        val record = override ?: series
        val occurrenceStart = override?.startsAt
            ?: zone.atLocalDateTimeMillis(originalDate, zone.localTimeOfDayMillis(series.startsAt))
        val occurrenceEnd = override?.endsAt ?: (occurrenceStart + (series.endsAt - series.startsAt))

        val workspace = record.workspaceId?.let { repository.findWorkspaceById(it) }
        val seriesWorkspace = if (series.workspaceId == record.workspaceId) {
            workspace
        } else {
            series.workspaceId?.let { repository.findWorkspaceById(it) }
        }
        val accessibleIds = setOfNotNull(workspace?.id, seriesWorkspace?.id)
        if (!isVisibleFixedEvent(record, repository.ownerUserId, accessibleIds) &&
            !isVisibleFixedEvent(series, repository.ownerUserId, accessibleIds)
        ) {
            return null
        }

        val editable = canEditFixedEvent(record, repository.ownerUserId, workspace) &&
            canEditFixedEvent(series, repository.ownerUserId, seriesWorkspace)
        val archived = workspace?.status == WorkspaceStatus.ARCHIVED ||
            seriesWorkspace?.status == WorkspaceStatus.ARCHIVED

        return FixedEventDetail(
            target = FixedEventTarget.Occurrence(series.id, originalDate),
            record = record,
            series = series,
            occurrenceStart = occurrenceStart,
            occurrenceEnd = occurrenceEnd,
            initialDraft = record.toDraft(
                occurrenceStart = occurrenceStart,
                occurrenceEnd = occurrenceEnd,
                repeatWeekdays = series.repeatWeekdays,
                repeatUntil = series.repeatUntil,
            ),
            workspace = workspace ?: seriesWorkspace,
            workspaceArchived = archived,
            isSharedReadOnly = record.createdBy != repository.ownerUserId ||
                series.createdBy != repository.ownerUserId,
            canEdit = editable,
            canDelete = editable,
            supportsScopeSelection = true,
        )
    }
}
