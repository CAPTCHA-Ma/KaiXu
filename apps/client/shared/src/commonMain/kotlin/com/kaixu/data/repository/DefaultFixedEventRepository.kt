package com.kaixu.data.repository

import com.kaixu.core.platform.CalendarDate
import com.kaixu.data.datasource.FixedEventDataSource
import com.kaixu.data.datasource.TaskDataSource
import com.kaixu.data.datasource.WorkSessionDataSource
import com.kaixu.data.datasource.WorkspaceDataSource
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.data.datasource.local.entity.newLocalId
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.repository.DirectTransaction
import com.kaixu.domain.repository.FixedEventRepository
import com.kaixu.domain.repository.FixedEventWriteScope
import com.kaixu.domain.repository.LocalDataRevision
import com.kaixu.domain.repository.LocalDataRevisionTracker
import com.kaixu.domain.repository.LocalTransaction
import com.kaixu.domain.validation.scheduling.FixedEventForm
import com.kaixu.domain.validation.scheduling.SchedulingInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private sealed interface StagedFixedEventMutation {
    data class Insert(val event: FixedEvent) : StagedFixedEventMutation
    data class Replace(val event: FixedEvent) : StagedFixedEventMutation
    data class Delete(val id: String) : StagedFixedEventMutation
    data class Truncate(val id: String, val until: String, val updated: FixedEvent?) : StagedFixedEventMutation
}

/** 协调本地固定事件与排期上下文数据源；成功提交后才发布新快照并递增修订号，失败不伪装成保存成功。 */
class DefaultFixedEventRepository(
    private val source: FixedEventDataSource,
    private val workspaces: WorkspaceDataSource? = null,
    private val workSessions: WorkSessionDataSource? = null,
    private val tasks: TaskDataSource? = null,
    override val ownerUserId: String = LOCAL_ACTOR_ID,
    private val transaction: LocalTransaction = DirectTransaction,
    private val revisionTracker: LocalDataRevision = LocalDataRevisionTracker(),
) : FixedEventRepository {
    init {
        SchedulingInput.requireId(ownerUserId)
    }

    private val _events = MutableStateFlow<List<FixedEvent>>(emptyList())
    override val events: StateFlow<List<FixedEvent>> = _events.asStateFlow()
    override val revision: StateFlow<Long> = revisionTracker.version
    private val mutex = Mutex()

    override suspend fun refresh() = mutex.withLock {
        val loaded = source.listEvents().sortedEvents()
        val changed = _events.value != loaded
        _events.value = loaded
        if (changed) {
            revisionTracker.notifyChanged()
        }
    }

    override suspend fun listAll(): List<FixedEvent> =
        source.listEvents().sortedEvents()

    override suspend fun listOverridesIncludingDeleted(): List<FixedEvent> =
        source.listOverridesIncludingDeleted()

    override suspend fun create(fixedEvent: FixedEvent): FixedEvent =
        writeTransaction { insertEvent(fixedEvent) }

    override suspend fun update(fixedEvent: FixedEvent): Boolean {
        if (fixedEvent.id.isBlank()) return false
        return writeTransaction { replaceEvent(fixedEvent) }
    }

    override suspend fun deleteById(id: String): Boolean {
        if (id.isBlank()) return false
        return writeTransaction { removeEventById(id) }
    }

    override suspend fun findById(id: String): FixedEvent? {
        FixedEventForm.requireId(id)
        return source.findEventById(id)
    }

    override suspend fun listByWorkspace(workspaceId: String): List<FixedEvent> {
        SchedulingInput.requireId(workspaceId)
        return source.listEventsByWorkspace(workspaceId).sortedEvents()
    }

    override suspend fun listInRange(ownerId: String, start: Long, end: Long): List<FixedEvent> {
        SchedulingInput.requireId(ownerId)
        SchedulingInput.requireRange(start, end)
        return source.listEventsInRange(ownerId, start, end).sortedEvents()
    }

    override suspend fun findWorkspaceById(id: String): Workspace? {
        SchedulingInput.requireId(id)
        return workspaces?.findWorkspaceById(id)?.takeIf { it.ownerUserId == ownerUserId }
    }

    override suspend fun listWorkspaces(status: WorkspaceStatus?): List<Workspace> =
        workspaces?.listWorkspaces(status)
            ?.filter { it.ownerUserId == ownerUserId }
            .orEmpty()

    override suspend fun listOwnerWorkSessionsInRange(start: Long, end: Long): List<WorkSession> {
        SchedulingInput.requireRange(start, end)
        return workSessions?.listWorkSessionsInRange(ownerUserId, start, end)
            ?.filter { it.ownerUserId == ownerUserId && it.startsAt < end && it.endsAt > start }
            ?.sortedWith(compareBy(WorkSession::startsAt, WorkSession::id))
            .orEmpty()
    }

    override suspend fun listTasks(): List<Task> =
        tasks?.listTasks().orEmpty()

    override suspend fun findTaskById(id: String): Task? {
        SchedulingInput.requireId(id)
        return tasks?.findTaskById(id)
    }

    override suspend fun <R> writeTransaction(block: suspend FixedEventWriteScope.() -> R): R = mutex.withLock {
        val stagedMutations = mutableListOf<StagedFixedEventMutation>()
        var mutated = false
        val scope = object : FixedEventWriteScope {
            override val ownerUserId: String
                get() = this@DefaultFixedEventRepository.ownerUserId

            override suspend fun listAllEvents(): List<FixedEvent> =
                this@DefaultFixedEventRepository.listAll()

            override suspend fun listOverridesIncludingDeleted(): List<FixedEvent> =
                this@DefaultFixedEventRepository.listOverridesIncludingDeleted()

            override suspend fun findEventById(id: String): FixedEvent? =
                this@DefaultFixedEventRepository.findById(id)

            override suspend fun findWorkspaceById(id: String): Workspace? =
                this@DefaultFixedEventRepository.findWorkspaceById(id)

            override suspend fun listOwnerWorkSessionsInRange(start: Long, end: Long): List<WorkSession> =
                this@DefaultFixedEventRepository.listOwnerWorkSessionsInRange(start, end)

            override suspend fun findTaskById(id: String): Task? =
                this@DefaultFixedEventRepository.findTaskById(id)

            override suspend fun insertEvent(event: FixedEvent): FixedEvent {
                val toSave = if (event.id.isBlank()) event.copy(id = newLocalId()) else event
                val saved = source.createEvent(toSave)
                stagedMutations += StagedFixedEventMutation.Insert(saved)
                mutated = true
                return saved
            }

            override suspend fun replaceEvent(event: FixedEvent): Boolean {
                FixedEventForm.requireId(event.id)
                val updated = source.updateEvent(event)
                if (updated) {
                    val saved = source.findEventById(event.id) ?: event
                    stagedMutations += StagedFixedEventMutation.Replace(saved)
                    mutated = true
                }
                return updated
            }

            override suspend fun removeEventById(id: String): Boolean {
                FixedEventForm.requireId(id)
                val deleted = source.deleteEventById(id)
                if (deleted) {
                    stagedMutations += StagedFixedEventMutation.Delete(id)
                    mutated = true
                }
                return deleted
            }

            override suspend fun truncateSeriesUntil(id: String, until: String): Boolean {
                FixedEventForm.requireId(id)
                require(CalendarDate.isValidDate(until)) { "截断日期无效：$until" }
                val truncated = source.truncateSeriesUntil(id, until)
                if (truncated) {
                    val updated = source.findEventById(id)
                    stagedMutations += StagedFixedEventMutation.Truncate(id, until, updated)
                    mutated = true
                }
                return truncated
            }
        }

        val result = transaction.run { scope.block() }

        if (stagedMutations.isNotEmpty()) {
            var next = _events.value
            for (mutation in stagedMutations) {
                next = when (mutation) {
                    is StagedFixedEventMutation.Insert ->
                        (next.filterNot { it.id == mutation.event.id } + mutation.event).sortedEvents()
                    is StagedFixedEventMutation.Replace ->
                        (next.filterNot { it.id == mutation.event.id } + mutation.event).sortedEvents()
                    is StagedFixedEventMutation.Delete ->
                        next.filterNot { it.id == mutation.id }
                    is StagedFixedEventMutation.Truncate -> {
                        if (next.any { it.id == mutation.id }) {
                            next.map {
                                if (it.id == mutation.id) {
                                    mutation.updated ?: it.copy(repeatUntil = mutation.until)
                                } else {
                                    it
                                }
                            }
                        } else {
                            mutation.updated?.let { (next + it).sortedEvents() } ?: next
                        }
                    }
                }
            }
            _events.value = next
        }
        if (mutated) {
            revisionTracker.notifyChanged()
        }
        result
    }

    private fun List<FixedEvent>.sortedEvents(): List<FixedEvent> =
        sortedWith(compareBy({ it.startsAt }, { it.id }))
}
