package com.kaixu.app.di

import com.kaixu.data.datasource.FixedEventDataSource
import com.kaixu.data.datasource.FocusDataSource
import com.kaixu.data.datasource.TaskDataSource
import com.kaixu.data.datasource.WorkSessionDataSource
import com.kaixu.data.datasource.WorkspaceDataSource
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.data.repository.DefaultFixedEventRepository
import com.kaixu.data.repository.DefaultFocusRepository
import com.kaixu.data.repository.DefaultTaskRepository
import com.kaixu.data.repository.DefaultWorkSessionRepository
import com.kaixu.domain.model.scheduling.FocusDuration
import com.kaixu.domain.model.scheduling.FocusSegment
import com.kaixu.domain.model.scheduling.FocusSession
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.repository.DirectTransaction
import com.kaixu.domain.repository.FixedEventRepository
import com.kaixu.domain.repository.FocusRepository
import com.kaixu.domain.repository.LocalDataRevision
import com.kaixu.domain.repository.LocalDataRevisionTracker
import com.kaixu.domain.repository.LocalTransaction
import com.kaixu.domain.repository.TaskRepository
import com.kaixu.domain.repository.WorkSessionRepository
import com.kaixu.domain.usecase.scheduling.CreateFixedEventUseCase
import com.kaixu.domain.usecase.scheduling.DeleteFixedEventUseCase
import com.kaixu.domain.usecase.scheduling.FindFixedEventByIdUseCase
import com.kaixu.domain.usecase.scheduling.FixedEventUseCases
import com.kaixu.domain.usecase.scheduling.FocusUseCases
import com.kaixu.domain.usecase.scheduling.ListFixedEventsByWorkspaceUseCase
import com.kaixu.domain.usecase.scheduling.ListFixedEventsInRangeUseCase
import com.kaixu.domain.usecase.scheduling.ObserveFixedEventsUseCase
import com.kaixu.domain.usecase.scheduling.RefreshFixedEventsUseCase
import com.kaixu.domain.usecase.scheduling.UpdateFixedEventUseCase
import com.kaixu.domain.usecase.scheduling.WorkSessionUseCases
import com.kaixu.domain.usecase.tasks.TaskUseCases

private object UnconfiguredWorkSessionDataSource : WorkSessionDataSource {
    private fun unsupported(): Nothing = error("当前 AppGraph 未注入 WorkSessionDataSource")
    override suspend fun listWorkSessions(): List<WorkSession> = unsupported()
    override suspend fun findWorkSessionById(id: String): WorkSession? = unsupported()
    override suspend fun createWorkSession(value: WorkSession): WorkSession = unsupported()
    override suspend fun createWorkSessions(values: List<WorkSession>): List<WorkSession> = unsupported()
    override suspend fun updateWorkSession(value: WorkSession): Boolean = unsupported()
    override suspend fun deleteWorkSessionById(id: String): Boolean = unsupported()
    override suspend fun listWorkSessionsByTask(taskId: String): List<WorkSession> = unsupported()
    override suspend fun listWorkSessionsByOwnerAndTask(ownerId: String, taskId: String): List<WorkSession> = unsupported()
    override suspend fun listWorkSessionsInRange(ownerId: String, start: Long, end: Long): List<WorkSession> = unsupported()
}

private object UnconfiguredFocusDataSource : FocusDataSource {
    private fun unsupported(): Nothing = error("当前 AppGraph 未注入 FocusDataSource")
    override suspend fun createFocusSession(value: FocusSession): FocusSession = unsupported()
    override suspend fun findFocusSessionById(id: String): FocusSession? = unsupported()
    override suspend fun updateFocusSession(value: FocusSession): Boolean = unsupported()
    override suspend fun listFocusSessions(ownerId: String, workSessionId: String): List<FocusSession> = unsupported()
    override suspend fun appendFocusSegment(value: FocusSegment): FocusSegment = unsupported()
    override suspend fun listFocusSegments(sessionId: String): List<FocusSegment> = unsupported()
    override suspend fun getFocusDuration(ownerId: String, workSessionId: String): FocusDuration = unsupported()
}

/** 组合根：平台显式注入数据源，并管理数据库生命周期。四类核心仓储共享同一事务与本地修订信号。 */
class AppGraph(
    source: TaskDataSource,
    fixedEventSource: FixedEventDataSource,
    workspaces: WorkspaceDataSource? = null,
    workSessions: WorkSessionDataSource? = null,
    focusSource: FocusDataSource? = null,
    ownerUserId: String = LOCAL_ACTOR_ID,
    transaction: LocalTransaction = DirectTransaction,
    val localRevision: LocalDataRevision = LocalDataRevisionTracker(),
) {
    constructor(
        sources: LocalDataSources,
        ownerUserId: String = LOCAL_ACTOR_ID,
        localRevision: LocalDataRevision = LocalDataRevisionTracker(),
    ) : this(
        source = sources.tasks,
        fixedEventSource = sources.fixedEvents,
        workspaces = sources.workspaces,
        workSessions = sources.workSessions,
        focusSource = sources.focus,
        ownerUserId = ownerUserId,
        transaction = sources.transaction,
        localRevision = localRevision,
    )

    val taskRepository: TaskRepository = DefaultTaskRepository(
        source = source,
        workspaces = workspaces,
        workSessions = workSessions,
        ownerUserId = ownerUserId,
        transaction = transaction,
        revisionTracker = localRevision,
    )
    val taskUseCases = TaskUseCases(taskRepository)

    val fixedEventRepository: FixedEventRepository = DefaultFixedEventRepository(
        source = fixedEventSource,
        workspaces = workspaces,
        workSessions = workSessions,
        tasks = source,
        ownerUserId = ownerUserId,
        transaction = transaction,
        revisionTracker = localRevision,
    )
    val fixedEventUseCases = FixedEventUseCases(fixedEventRepository)

    val workSessionRepository: WorkSessionRepository = DefaultWorkSessionRepository(
        source = workSessions ?: UnconfiguredWorkSessionDataSource,
        tasks = source,
        events = fixedEventSource,
        workspaces = workspaces,
        ownerUserId = ownerUserId,
        transaction = transaction,
        revisionTracker = localRevision,
        focus = focusSource,
    )
    val focusRepository: FocusRepository = DefaultFocusRepository(
        source = focusSource ?: UnconfiguredFocusDataSource,
        ownerUserId = ownerUserId,
        transaction = transaction,
        revisionTracker = localRevision,
        workSessions = workSessions,
        tasks = source,
        workspaces = workspaces,
    )
    val workSessionUseCases = WorkSessionUseCases(workSessionRepository)
    val focusUseCases = FocusUseCases(
        repository = focusRepository,
        workSessionRepository = workSessionRepository.takeIf { workSessions != null },
    )

    val observeFixedEvents = ObserveFixedEventsUseCase(fixedEventRepository)
    val refreshFixedEvents = RefreshFixedEventsUseCase(fixedEventRepository)
    val createFixedEvent = CreateFixedEventUseCase(fixedEventRepository)
    val updateFixedEvent = UpdateFixedEventUseCase(fixedEventRepository)
    val deleteFixedEvent = DeleteFixedEventUseCase(fixedEventRepository)
    val findFixedEventById = FindFixedEventByIdUseCase(fixedEventRepository)
    val listFixedEventsByWorkspace = ListFixedEventsByWorkspaceUseCase(fixedEventRepository)
    val listFixedEventsInRange = ListFixedEventsInRangeUseCase(fixedEventRepository)
}
