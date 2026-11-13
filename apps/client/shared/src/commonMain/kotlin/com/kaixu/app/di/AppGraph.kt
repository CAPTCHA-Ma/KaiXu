package com.kaixu.app.di

import com.kaixu.data.datasource.FixedEventDataSource
import com.kaixu.data.datasource.TaskDataSource
import com.kaixu.data.repository.DefaultFixedEventRepository
import com.kaixu.data.repository.DefaultTaskRepository
import com.kaixu.domain.repository.FixedEventRepository
import com.kaixu.domain.repository.TaskRepository
import com.kaixu.domain.usecase.scheduling.CreateFixedEventUseCase
import com.kaixu.domain.usecase.scheduling.DeleteFixedEventUseCase
import com.kaixu.domain.usecase.scheduling.FindFixedEventByIdUseCase
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
) {
    val taskRepository: TaskRepository = DefaultTaskRepository(source)
    val taskUseCases = TaskUseCases(taskRepository)

    val fixedEventRepository: FixedEventRepository = DefaultFixedEventRepository(fixedEventSource)
    val observeFixedEvents = ObserveFixedEventsUseCase(fixedEventRepository)
    val refreshFixedEvents = RefreshFixedEventsUseCase(fixedEventRepository)
    val createFixedEvent = CreateFixedEventUseCase(fixedEventRepository)
    val updateFixedEvent = UpdateFixedEventUseCase(fixedEventRepository)
    val deleteFixedEvent = DeleteFixedEventUseCase(fixedEventRepository)
    val findFixedEventById = FindFixedEventByIdUseCase(fixedEventRepository)
    val listFixedEventsByWorkspace = ListFixedEventsByWorkspaceUseCase(fixedEventRepository)
    val listFixedEventsInRange = ListFixedEventsInRangeUseCase(fixedEventRepository)
}
