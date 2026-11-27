package com.kaixu.app.di

import com.kaixu.core.platform.LocalZone
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.data.repository.DefaultFocusRepository
import com.kaixu.data.repository.DefaultWorkSessionRepository
import com.kaixu.domain.repository.*
import com.kaixu.domain.usecase.scheduling.*

/** 本地排期兼容组合根：统一委托至基于 [LocalZone] 与 [LocalDataRevision] 的新版仓储与用例门面。 */
class LocalSchedulingGraph(
    sources: LocalDataSources,
    ownerUserId: String = LOCAL_ACTOR_ID,
    defaultZone: LocalZone = LocalZone.systemDefault(),
    val localRevision: LocalDataRevision = LocalDataRevisionTracker(),
) {
    private val transaction: LocalTransaction = sources.transaction
    val workSessionRepository: WorkSessionRepository = DefaultWorkSessionRepository(
        source = sources.workSessions,
        tasks = sources.tasks,
        events = sources.fixedEvents,
        workspaces = sources.workspaces,
        ownerUserId = ownerUserId,
        transaction = transaction,
        revisionTracker = localRevision,
        focus = sources.focus,
    )
    val focusRepository: FocusRepository = DefaultFocusRepository(
        source = sources.focus,
        ownerUserId = ownerUserId,
        transaction = transaction,
        revisionTracker = localRevision,
        workSessions = sources.workSessions,
        tasks = sources.tasks,
        workspaces = sources.workspaces,
    )
    val workSessionUseCases = WorkSessionUseCases(workSessionRepository, defaultZone)
    val focusUseCases = FocusUseCases(focusRepository, defaultZone, workSessionRepository)

    val createWorkSessions = CreateWorkSessionsUseCase(workSessionRepository, transaction, defaultZone)
    val updateWorkSession = UpdateWorkSessionUseCase(workSessionRepository, focusRepository, transaction, defaultZone)
    val changeWorkSessionStatus = ChangeWorkSessionStatusUseCase(workSessionRepository, transaction)
    val deleteWorkSession = DeleteWorkSessionUseCase(workSessionRepository, transaction)
    val getPersonalSchedule = GetPersonalScheduleUseCase(workSessionRepository, transaction, defaultZone)
    val startFocusSession = StartFocusSessionUseCase(workSessionRepository, focusRepository, transaction)
    val controlFocusSession = ControlFocusSessionUseCase(workSessionRepository, focusRepository, transaction)
    val recordFocusSegment = RecordFocusSegmentUseCase(focusRepository, transaction)
    val getFocusSummary = GetFocusSummaryUseCase(focusRepository, transaction)
    val fixedEventSchedule = FixedEventSchedule(
        sources.fixedEvents, sources.workSessions, sources.workspaces, ownerUserId, transaction,
    )
}
