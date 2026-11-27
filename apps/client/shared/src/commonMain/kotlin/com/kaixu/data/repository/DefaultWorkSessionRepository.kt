package com.kaixu.data.repository

import com.kaixu.data.datasource.FixedEventDataSource
import com.kaixu.data.datasource.FocusDataSource
import com.kaixu.data.datasource.TaskDataSource
import com.kaixu.data.datasource.WorkSessionDataSource
import com.kaixu.data.datasource.WorkspaceDataSource
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.FocusDuration
import com.kaixu.domain.model.scheduling.FocusSession
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.repository.DirectTransaction
import com.kaixu.domain.repository.LocalDataRevision
import com.kaixu.domain.repository.LocalDataRevisionTracker
import com.kaixu.domain.repository.LocalTransaction
import com.kaixu.domain.repository.WorkSessionRepository
import com.kaixu.domain.repository.WorkSessionWriteScope
import com.kaixu.domain.validation.scheduling.SchedulingInput
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 协调本人个人工作时段及关联排期上下文数据源。
 * 不持有独立列表缓存，避免任务删除脱链或完成取消后产生过时快照；
 * 纯读取方法不加互斥锁以免事务内重入死锁，写事务仅在提交成功且发生修改后递增修订号。
 */
class DefaultWorkSessionRepository(
    private val source: WorkSessionDataSource,
    private val tasks: TaskDataSource? = null,
    private val events: FixedEventDataSource? = null,
    private val workspaces: WorkspaceDataSource? = null,
    override val ownerUserId: String = LOCAL_ACTOR_ID,
    private val transaction: LocalTransaction = DirectTransaction,
    private val revisionTracker: LocalDataRevision = LocalDataRevisionTracker(),
    private val focus: FocusDataSource? = null,
) : WorkSessionRepository {
    init {
        SchedulingInput.requireId(ownerUserId)
    }

    override val revision: StateFlow<Long> = revisionTracker.version
    private val mutex = Mutex()

    override suspend fun findById(id: String): WorkSession? {
        SchedulingInput.requireId(id)
        return source.findWorkSessionById(id)?.takeIf { it.ownerUserId == ownerUserId }
    }

    override suspend fun listByTask(taskId: String): List<WorkSession> {
        SchedulingInput.requireId(taskId)
        return source.listWorkSessionsByOwnerAndTask(ownerUserId, taskId)
            .filter { it.ownerUserId == ownerUserId && it.taskId == taskId }
            .sortedByTime()
    }

    override suspend fun listInRange(start: Long, end: Long): List<WorkSession> {
        SchedulingInput.requireRange(start, end)
        return source.listWorkSessionsInRange(ownerUserId, start, end)
            .filter { it.ownerUserId == ownerUserId && it.startsAt < end && it.endsAt > start }
            .sortedByTime()
    }

    override suspend fun createAll(values: List<WorkSession>): List<WorkSession> =
        writeTransaction { insertSessions(values) }

    override suspend fun update(value: WorkSession): Boolean =
        writeTransaction { replaceSession(value) }

    override suspend fun deleteById(id: String): Boolean {
        if (id.isBlank()) return false
        return writeTransaction { removeSessionById(id) }
    }

    override suspend fun findTaskById(id: String): Task? {
        SchedulingInput.requireId(id)
        val task = tasks?.findTaskById(id) ?: return null
        val workspaceId = task.workspaceId
        val accessibleByOwner = task.createdBy == ownerUserId ||
            task.assigneeUserId == ownerUserId ||
            (workspaceId != null && findWorkspaceById(workspaceId) != null)
        return task.takeIf { accessibleByOwner }
    }

    override suspend fun findWorkspaceById(id: String): Workspace? {
        SchedulingInput.requireId(id)
        return workspaces?.findWorkspaceById(id)?.takeIf { it.ownerUserId == ownerUserId }
    }

    override suspend fun listWorkspaces(status: WorkspaceStatus?): List<Workspace> =
        workspaces?.listWorkspaces(status)
            ?.filter { it.ownerUserId == ownerUserId }
            .orEmpty()

    override suspend fun listFixedEventsInRange(start: Long, end: Long): List<FixedEvent> {
        SchedulingInput.requireRange(start, end)
        return events?.listEventsInRange(ownerUserId, start, end)
            ?.filter { it.createdBy == ownerUserId && it.startsAt < end && it.endsAt > start }
            ?.sortedEvents()
            .orEmpty()
    }

    override suspend fun listAllFixedEvents(): List<FixedEvent> =
        events?.listEvents()
            ?.filter { it.createdBy == ownerUserId }
            ?.sortedEvents()
            .orEmpty()

    override suspend fun listFixedEventOverridesIncludingDeleted(): List<FixedEvent> =
        events?.listOverridesIncludingDeleted()
            ?.filter { it.createdBy == ownerUserId }
            .orEmpty()

    override suspend fun listFocusSessions(workSessionId: String): List<FocusSession> {
        SchedulingInput.requireId(workSessionId)
        return focus?.listFocusSessions(ownerUserId, workSessionId)
            ?.filter { it.ownerUserId == ownerUserId && it.workSessionId == workSessionId }
            ?.sortedWith(compareBy(FocusSession::startedAt, FocusSession::id))
            .orEmpty()
    }

    override suspend fun getFocusDuration(workSessionId: String): FocusDuration {
        SchedulingInput.requireId(workSessionId)
        return focus?.getFocusDuration(ownerUserId, workSessionId) ?: FocusDuration()
    }

    override suspend fun <R> writeTransaction(block: suspend WorkSessionWriteScope.() -> R): R = mutex.withLock {
        var mutated = false
        val scope = object : WorkSessionWriteScope {
            override val ownerUserId: String
                get() = this@DefaultWorkSessionRepository.ownerUserId

            override suspend fun findById(id: String): WorkSession? =
                this@DefaultWorkSessionRepository.findById(id)

            override suspend fun listByTask(taskId: String): List<WorkSession> =
                this@DefaultWorkSessionRepository.listByTask(taskId)

            override suspend fun listInRange(start: Long, end: Long): List<WorkSession> =
                this@DefaultWorkSessionRepository.listInRange(start, end)

            override suspend fun findTaskById(id: String): Task? =
                this@DefaultWorkSessionRepository.findTaskById(id)

            override suspend fun findWorkspaceById(id: String): Workspace? =
                this@DefaultWorkSessionRepository.findWorkspaceById(id)

            override suspend fun listAllFixedEvents(): List<FixedEvent> =
                this@DefaultWorkSessionRepository.listAllFixedEvents()

            override suspend fun listFixedEventOverridesIncludingDeleted(): List<FixedEvent> =
                this@DefaultWorkSessionRepository.listFixedEventOverridesIncludingDeleted()

            override suspend fun listFocusSessions(workSessionId: String): List<FocusSession> =
                this@DefaultWorkSessionRepository.listFocusSessions(workSessionId)

            override suspend fun getFocusDuration(workSessionId: String): FocusDuration =
                this@DefaultWorkSessionRepository.getFocusDuration(workSessionId)

            override suspend fun insertSessions(values: List<WorkSession>): List<WorkSession> {
                if (values.isEmpty()) return emptyList()
                val checked = values.map { SchedulingInput.workSession(it).also(::requireOwner) }
                val saved = source.createWorkSessions(checked)
                if (saved.isNotEmpty()) {
                    mutated = true
                }
                return saved
            }

            override suspend fun replaceSession(value: WorkSession): Boolean {
                val checked = SchedulingInput.workSession(value).also(::requireOwner)
                val existing = findById(checked.id) ?: return false
                val updated = source.updateWorkSession(checked)
                if (updated && existing != checked) {
                    mutated = true
                }
                return updated
            }

            override suspend fun removeSessionById(id: String): Boolean {
                SchedulingInput.requireId(id)
                if (findById(id) == null) return false
                val deleted = source.deleteWorkSessionById(id)
                if (deleted) {
                    mutated = true
                }
                return deleted
            }
        }

        val result = transaction.run { scope.block() }
        if (mutated) {
            revisionTracker.notifyChanged()
        }
        result
    }

    private fun requireOwner(value: WorkSession) =
        require(value.ownerUserId == ownerUserId) { "只能保存本人计划时段" }

    private fun List<WorkSession>.sortedByTime(): List<WorkSession> =
        sortedWith(compareBy(WorkSession::startsAt, WorkSession::id))

    private fun List<FixedEvent>.sortedEvents(): List<FixedEvent> =
        sortedWith(compareBy(FixedEvent::startsAt, FixedEvent::id))
}
