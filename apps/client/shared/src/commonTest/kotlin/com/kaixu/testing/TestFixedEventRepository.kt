package com.kaixu.testing

import com.kaixu.core.platform.CalendarDate
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.repository.FixedEventRepository
import com.kaixu.domain.repository.FixedEventWriteScope
import com.kaixu.domain.repository.LocalDataRevision
import com.kaixu.domain.repository.LocalDataRevisionTracker
import com.kaixu.domain.validation.scheduling.FixedEventForm
import com.kaixu.domain.validation.scheduling.SchedulingInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 固定事件仓储的测试替身：记录调用次数，可注入失败并支持事务回滚，不做真实持久化。 */
class TestFixedEventRepository(
    initial: List<FixedEvent> = emptyList(),
    override val ownerUserId: String = "00000000-0000-0000-0000-000000000001",
    private val revisionTracker: LocalDataRevision = LocalDataRevisionTracker(),
) : FixedEventRepository {
    private val _events = MutableStateFlow(initial.filterNot { it.isCancelled }.sortedEvents())
    override val events: StateFlow<List<FixedEvent>> = _events.asStateFlow()
    override val revision: StateFlow<Long> = revisionTracker.version

    private val cancelledOverrides = linkedMapOf<String, FixedEvent>().apply {
        initial.filter { it.isCancelled && it.seriesId != null }.forEach { put(it.id, it) }
    }
    private val workspaces = linkedMapOf<String, Workspace>()
    private val workSessions = linkedMapOf<String, WorkSession>()
    private val tasks = linkedMapOf<String, Task>()
    private var generatedIdCounter = 0

    var createCalls = 0
    var updateCalls = 0
    var deleteCalls = 0
    var refreshCalls = 0
    var failNextWrite = false
    var failNextRefresh = false
    var refreshResult: List<FixedEvent>? = null
    var lastSaved: FixedEvent? = null

    fun putWorkspace(workspace: Workspace) {
        workspaces[workspace.id] = workspace
    }

    fun putWorkSession(session: WorkSession) {
        workSessions[session.id] = session
    }

    fun putTask(task: Task) {
        tasks[task.id] = task
    }

    fun putEvent(event: FixedEvent) {
        if (event.isCancelled && event.seriesId != null) {
            cancelledOverrides[event.id] = event
            _events.value = _events.value.filterNot { it.id == event.id }
        } else {
            cancelledOverrides.remove(event.id)
            _events.value = (_events.value.filterNot { it.id == event.id } + event).sortedEvents()
        }
    }

    override suspend fun refresh() {
        refreshCalls++
        if (failNextRefresh) {
            failNextRefresh = false
            error("测试读取失败")
        }
        refreshResult?.let { loaded ->
            val next = loaded.filterNot { it.isCancelled }.sortedEvents()
            val changed = _events.value != next
            _events.value = next
            cancelledOverrides.clear()
            loaded.filter { it.isCancelled && it.seriesId != null }.forEach { cancelledOverrides[it.id] = it }
            if (changed) {
                revisionTracker.notifyChanged()
            }
        }
    }

    override suspend fun listAll(): List<FixedEvent> = _events.value

    override suspend fun listOverridesIncludingDeleted(): List<FixedEvent> =
        (_events.value.filter { it.seriesId != null } + cancelledOverrides.values)
            .sortedWith(compareBy({ it.seriesId }, { it.originalDate }, { it.id }))

    override suspend fun create(fixedEvent: FixedEvent): FixedEvent {
        createCalls++
        return writeTransaction { insertEvent(fixedEvent) }
    }

    override suspend fun update(fixedEvent: FixedEvent): Boolean {
        updateCalls++
        return writeTransaction { replaceEvent(fixedEvent) }
    }

    override suspend fun deleteById(id: String): Boolean {
        deleteCalls++
        return writeTransaction { removeEventById(id) }
    }

    override suspend fun findById(id: String): FixedEvent? {
        FixedEventForm.requireId(id)
        return _events.value.firstOrNull { it.id == id }
    }

    override suspend fun listByWorkspace(workspaceId: String): List<FixedEvent> {
        SchedulingInput.requireId(workspaceId)
        return _events.value.filter { it.workspaceId == workspaceId }
    }

    override suspend fun listInRange(ownerId: String, start: Long, end: Long): List<FixedEvent> {
        SchedulingInput.requireId(ownerId)
        SchedulingInput.requireRange(start, end)
        return _events.value.filter { it.createdBy == ownerId && it.startsAt < end && it.endsAt > start }
    }

    override suspend fun findWorkspaceById(id: String): Workspace? {
        SchedulingInput.requireId(id)
        return workspaces[id]?.takeIf { it.ownerUserId == ownerUserId }
    }

    override suspend fun listWorkspaces(status: WorkspaceStatus?): List<Workspace> =
        workspaces.values.filter { it.ownerUserId == ownerUserId && (status == null || it.status == status) }

    override suspend fun listOwnerWorkSessionsInRange(start: Long, end: Long): List<WorkSession> {
        SchedulingInput.requireRange(start, end)
        return workSessions.values
            .filter { it.ownerUserId == ownerUserId && it.startsAt < end && it.endsAt > start }
            .sortedWith(compareBy(WorkSession::startsAt, WorkSession::id))
    }

    override suspend fun listTasks(): List<Task> = tasks.values.toList()

    override suspend fun findTaskById(id: String): Task? {
        SchedulingInput.requireId(id)
        return tasks[id]
    }

    override suspend fun <R> writeTransaction(block: suspend FixedEventWriteScope.() -> R): R {
        val snapshotEvents = _events.value
        val snapshotCancelled = cancelledOverrides.toMap()
        val snapshotLastSaved = lastSaved
        val snapshotGenerated = generatedIdCounter
        var mutated = false

        val scope = object : FixedEventWriteScope {
            override val ownerUserId: String
                get() = this@TestFixedEventRepository.ownerUserId

            override suspend fun listAllEvents(): List<FixedEvent> =
                this@TestFixedEventRepository.listAll()

            override suspend fun listOverridesIncludingDeleted(): List<FixedEvent> =
                this@TestFixedEventRepository.listOverridesIncludingDeleted()

            override suspend fun findEventById(id: String): FixedEvent? =
                this@TestFixedEventRepository.findById(id)

            override suspend fun findWorkspaceById(id: String): Workspace? =
                this@TestFixedEventRepository.findWorkspaceById(id)

            override suspend fun listOwnerWorkSessionsInRange(start: Long, end: Long): List<WorkSession> =
                this@TestFixedEventRepository.listOwnerWorkSessionsInRange(start, end)

            override suspend fun findTaskById(id: String): Task? =
                this@TestFixedEventRepository.findTaskById(id)

            override suspend fun insertEvent(event: FixedEvent): FixedEvent {
                failIfRequested()
                val resolvedId = if (event.id.isBlank()) "generated-${++generatedIdCounter}" else event.id
                val saved = event.copy(id = resolvedId)
                lastSaved = saved
                if (saved.isCancelled && saved.seriesId != null) {
                    cancelledOverrides[saved.id] = saved
                    _events.value = _events.value.filterNot { it.id == saved.id }
                } else {
                    cancelledOverrides.remove(saved.id)
                    _events.value = (_events.value.filterNot { it.id == saved.id } + saved).sortedEvents()
                }
                mutated = true
                return saved
            }

            override suspend fun replaceEvent(event: FixedEvent): Boolean {
                failIfRequested()
                if (event.id.isBlank()) return false
                if (_events.value.none { it.id == event.id }) return false
                lastSaved = event
                _events.value = _events.value
                    .map { if (it.id == event.id) event else it }
                    .sortedEvents()
                mutated = true
                return true
            }

            override suspend fun removeEventById(id: String): Boolean {
                failIfRequested()
                if (id.isBlank()) return false
                val target = _events.value.firstOrNull { it.id == id } ?: return false
                _events.value = _events.value.filterNot { it.id == id }
                if (target.seriesId != null) {
                    cancelledOverrides[target.id] = target.copy(isCancelled = true)
                }
                mutated = true
                return true
            }

            override suspend fun truncateSeriesUntil(id: String, until: String): Boolean {
                failIfRequested()
                FixedEventForm.requireId(id)
                require(CalendarDate.isValidDate(until)) { "截断日期无效：$until" }
                val target = _events.value.firstOrNull { it.id == id } ?: return false
                val truncated = target.copy(repeatUntil = until)
                lastSaved = truncated
                _events.value = _events.value
                    .map { if (it.id == id) truncated else it }
                    .sortedEvents()
                mutated = true
                return true
            }
        }

        return try {
            val result = scope.block()
            if (mutated) {
                revisionTracker.notifyChanged()
            }
            result
        } catch (failure: Throwable) {
            _events.value = snapshotEvents
            cancelledOverrides.clear()
            cancelledOverrides.putAll(snapshotCancelled)
            lastSaved = snapshotLastSaved
            generatedIdCounter = snapshotGenerated
            throw failure
        }
    }

    private fun failIfRequested() {
        if (failNextWrite) {
            failNextWrite = false
            error("测试写入失败")
        }
    }

    private fun List<FixedEvent>.sortedEvents(): List<FixedEvent> =
        sortedWith(compareBy({ it.startsAt }, { it.id }))
}
