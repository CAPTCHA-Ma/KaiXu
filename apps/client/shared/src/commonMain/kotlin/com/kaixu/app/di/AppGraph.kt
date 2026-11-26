package com.kaixu.app.di

import com.kaixu.data.datasource.FixedEventDataSource
import com.kaixu.data.datasource.TaskDataSource
import com.kaixu.data.datasource.WorkSessionDataSource
import com.kaixu.data.datasource.WorkspaceDataSource
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.data.repository.DefaultFixedEventRepository
import com.kaixu.data.repository.DefaultTaskRepository
import com.kaixu.domain.repository.DirectTransaction
import com.kaixu.domain.repository.FixedEventRepository
import com.kaixu.domain.repository.LocalDataRevision
import com.kaixu.domain.repository.LocalDataRevisionTracker
import com.kaixu.domain.repository.LocalTransaction
import com.kaixu.domain.repository.TaskRepository
import com.kaixu.domain.usecase.scheduling.CreateFixedEventUseCase
import com.kaixu.domain.usecase.scheduling.DeleteFixedEventUseCase
import com.kaixu.domain.usecase.scheduling.FindFixedEventByIdUseCase
import com.kaixu.domain.usecase.scheduling.FixedEventUseCases
import com.kaixu.domain.usecase.scheduling.ListFixedEventsByWorkspaceUseCase
import com.kaixu.domain.usecase.scheduling.ListFixedEventsInRangeUseCase
import com.kaixu.domain.usecase.scheduling.ObserveFixedEventsUseCase
import com.kaixu.domain.usecase.scheduling.RefreshFixedEventsUseCase
import com.kaixu.domain.usecase.scheduling.UpdateFixedEventUseCase
import com.kaixu.domain.usecase.tasks.TaskUseCases

/** 组合根：平台显式注入数据源，并管理数据库生命周期。 */
class AppGraph(
    source: TaskDataSource,
    fixedEventSource: FixedEventDataSource,
    workspaces: WorkspaceDataSource? = null,
    workSessions: WorkSessionDataSource? = null,
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
    val observeFixedEvents = ObserveFixedEventsUseCase(fixedEventRepository)
    val refreshFixedEvents = RefreshFixedEventsUseCase(fixedEventRepository)
    val createFixedEvent = CreateFixedEventUseCase(fixedEventRepository)
    val updateFixedEvent = UpdateFixedEventUseCase(fixedEventRepository)
    val deleteFixedEvent = DeleteFixedEventUseCase(fixedEventRepository)
    val findFixedEventById = FindFixedEventByIdUseCase(fixedEventRepository)
    val listFixedEventsByWorkspace = ListFixedEventsByWorkspaceUseCase(fixedEventRepository)
    val listFixedEventsInRange = ListFixedEventsInRangeUseCase(fixedEventRepository)
}
