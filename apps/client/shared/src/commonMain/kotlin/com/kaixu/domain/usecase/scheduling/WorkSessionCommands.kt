package com.kaixu.domain.usecase.scheduling

import com.kaixu.core.platform.LocalZone
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.Recurrence
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.scheduling.WorkSessionConfirmation
import com.kaixu.domain.model.scheduling.WorkSessionDraft
import com.kaixu.domain.model.scheduling.WorkSessionSaveResult
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.repository.DirectTransaction
import com.kaixu.domain.repository.FocusRepository
import com.kaixu.domain.repository.LocalTransaction
import com.kaixu.domain.repository.WorkSessionRepository
import com.kaixu.domain.validation.scheduling.SchedulingInput

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
internal fun newSchedulingId(): String = kotlin.uuid.Uuid.random().toString()

/** 兼容入口：内部统一委托至基于 [LocalZone] 与 [WorkSessionConflictChecker] 的 [CreateWorkSessionsCommandUseCase]。 */
class CreateWorkSessionsUseCase(
    repository: WorkSessionRepository,
    @Suppress("UNUSED_PARAMETER") transaction: LocalTransaction = DirectTransaction,
    private val defaultZone: LocalZone = LocalZone.systemDefault(),
) {
    private val delegate = CreateWorkSessionsCommandUseCase(repository, defaultZone)

    suspend operator fun invoke(
        drafts: List<WorkSessionDraft>,
        confirmed: WorkSessionConfirmation? = null,
        zone: LocalZone = defaultZone,
    ): WorkSessionSaveResult = delegate(drafts, confirmed, zone)
}

/** 兼容入口：保留基准快照 `original` 入参，内部统一委托至 [UpdateWorkSessionCommandUseCase]。 */
class UpdateWorkSessionUseCase(
    repository: WorkSessionRepository,
    focus: FocusRepository? = null,
    @Suppress("UNUSED_PARAMETER") transaction: LocalTransaction = DirectTransaction,
    private val defaultZone: LocalZone = LocalZone.systemDefault(),
) {
    private val delegate = UpdateWorkSessionCommandUseCase(repository, defaultZone, focus)

    suspend operator fun invoke(
        original: WorkSession,
        draft: WorkSessionDraft,
        confirmed: WorkSessionConfirmation? = null,
        zone: LocalZone = defaultZone,
    ): WorkSessionSaveResult = delegate(
        sessionId = original.id,
        draft = draft,
        expectedOriginal = original,
        confirmed = confirmed,
        zone = zone,
    )
}

/** 兼容入口：保留基准快照 `original` 入参，内部统一委托至 [ChangeWorkSessionStatusCommandUseCase]。 */
class ChangeWorkSessionStatusUseCase(
    repository: WorkSessionRepository,
    @Suppress("UNUSED_PARAMETER") transaction: LocalTransaction = DirectTransaction,
) {
    private val delegate = ChangeWorkSessionStatusCommandUseCase(repository)

    suspend operator fun invoke(
        original: WorkSession,
        status: WorkSessionStatus,
    ): WorkSession = delegate(
        sessionId = original.id,
        status = status,
        expectedOriginal = original,
    )
}

/** 兼容入口：保留基准快照 `original` 入参，内部统一委托至 [DeleteWorkSessionCommandUseCase]。 */
class DeleteWorkSessionUseCase(
    repository: WorkSessionRepository,
    @Suppress("UNUSED_PARAMETER") transaction: LocalTransaction = DirectTransaction,
) {
    private val delegate = DeleteWorkSessionCommandUseCase(repository)

    suspend operator fun invoke(original: WorkSession): Boolean = delegate(
        sessionId = original.id,
        expectedOriginal = original,
    )
}

data class PersonalSchedule(
    val events: List<FixedEvent>,
    val workSessions: List<WorkSession>,
)

/** 固定事件（含重复课次按 [LocalZone] 展开）和未取消计划时段聚合快照。 */
class GetPersonalScheduleUseCase(
    private val repository: WorkSessionRepository,
    @Suppress("UNUSED_PARAMETER") private val transaction: LocalTransaction = DirectTransaction,
    private val defaultZone: LocalZone = LocalZone.systemDefault(),
) {
    suspend operator fun invoke(
        start: Long,
        end: Long,
        zone: LocalZone = defaultZone,
    ): PersonalSchedule {
        SchedulingInput.requireRange(start, end)
        val occurrences = Recurrence.expand(
            events = repository.listAllFixedEvents(),
            overridesIncludingCancelled = repository.listFixedEventOverridesIncludingDeleted(),
            windowStart = start,
            windowEnd = end,
            zone = zone,
        )
        val expandedEvents = occurrences.map { occ ->
            FixedEvent(
                id = occ.key,
                title = occ.title,
                startsAt = occ.startsAt,
                endsAt = occ.endsAt,
                description = occ.description,
                workspaceId = occ.workspaceId,
                createdBy = occ.createdBy,
                reminderOffsetMinutes = occ.reminderOffsetMinutes,
                seriesId = occ.seriesId,
                originalDate = occ.originalDate,
                repeatWeekdays = occ.repeatWeekdays,
                repeatUntil = occ.repeatUntil,
            )
        }
        val activeSessions = repository.listInRange(start, end)
            .filter { it.status != WorkSessionStatus.CANCELLED }
        return PersonalSchedule(expandedEvents, activeSessions)
    }
}
