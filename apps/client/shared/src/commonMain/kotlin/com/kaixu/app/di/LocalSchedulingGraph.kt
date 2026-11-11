package com.kaixu.app.di

import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.repository.DefaultFocusRepository
import com.kaixu.data.repository.DefaultWorkSessionRepository
import com.kaixu.domain.repository.*
import com.kaixu.domain.usecase.scheduling.*

/** 本地排期组合根。所有仓储和用例共用同一数据库事务；不开放 Android 离线计时入口。 */
class LocalSchedulingGraph(sources: LocalDataSources, ownerUserId: String, dateAt: (Long) -> String) {
    private val transaction = object : SchedulingTransaction {
        override suspend fun <T> run(block: suspend () -> T): T = sources.writeTransaction(block)
    }
    val workSessionRepository: WorkSessionRepository = DefaultWorkSessionRepository(
        sources.workSessions, sources.tasks, sources.fixedEvents, sources.workspaces, ownerUserId, transaction,
    )
    val focusRepository: FocusRepository = DefaultFocusRepository(sources.focus, ownerUserId, transaction)
    val createWorkSessions = CreateWorkSessionsUseCase(workSessionRepository, transaction, dateAt)
    val updateWorkSession = UpdateWorkSessionUseCase(workSessionRepository, focusRepository, transaction, dateAt)
    val changeWorkSessionStatus = ChangeWorkSessionStatusUseCase(workSessionRepository, transaction)
    val deleteWorkSession = DeleteWorkSessionUseCase(workSessionRepository, transaction)
    val getPersonalSchedule = GetPersonalScheduleUseCase(workSessionRepository, transaction)
    val startFocusSession = StartFocusSessionUseCase(workSessionRepository, focusRepository, transaction)
    val controlFocusSession = ControlFocusSessionUseCase(workSessionRepository, focusRepository, transaction)
    val recordFocusSegment = RecordFocusSegmentUseCase(focusRepository, transaction)
    val getFocusSummary = GetFocusSummaryUseCase(focusRepository, transaction)
}
