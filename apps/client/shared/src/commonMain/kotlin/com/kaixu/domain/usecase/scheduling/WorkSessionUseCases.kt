package com.kaixu.domain.usecase.scheduling

import com.kaixu.core.platform.LocalZone
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.repository.WorkSessionRepository
import kotlinx.coroutines.flow.StateFlow

/** 个人工作时段的统一领域用例门面；共用同一个仓储与本地修订信号。 */
class WorkSessionUseCases(
    private val repository: WorkSessionRepository,
    defaultZone: LocalZone = LocalZone.systemDefault(),
) {
    val create = CreateWorkSessionsCommandUseCase(repository, defaultZone)
    val update = UpdateWorkSessionCommandUseCase(repository, defaultZone)
    val reschedule = RescheduleWorkSessionCommandUseCase(repository, defaultZone)
    val changeStatus = ChangeWorkSessionStatusCommandUseCase(repository)
    val delete = DeleteWorkSessionCommandUseCase(repository)
    val getDetail = GetWorkSessionDetailUseCase(repository)
    val queryByTask = QueryTaskWorkSessionsUseCase(repository)

    val revision: StateFlow<Long> get() = repository.revision

    suspend fun findById(id: String): WorkSession? = repository.findById(id)

    suspend fun listByTask(taskId: String): List<WorkSession> = repository.listByTask(taskId)

    suspend fun listInRange(start: Long, end: Long): List<WorkSession> = repository.listInRange(start, end)
}
