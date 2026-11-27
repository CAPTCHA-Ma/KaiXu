package com.kaixu.testing

import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.FocusDuration
import com.kaixu.domain.model.scheduling.FocusMode
import com.kaixu.domain.model.scheduling.FocusSegment
import com.kaixu.domain.model.scheduling.FocusSession
import com.kaixu.domain.model.scheduling.FocusSessionStatus
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.repository.FocusRepository
import com.kaixu.domain.repository.FocusWriteScope
import com.kaixu.domain.repository.LocalDataRevision
import com.kaixu.domain.repository.LocalDataRevisionTracker
import com.kaixu.domain.repository.WorkSessionRepository
import com.kaixu.domain.repository.WorkSessionWriteScope
import com.kaixu.domain.validation.scheduling.SchedulingInput
import kotlinx.coroutines.flow.StateFlow

/** 供 WorkSession 与 Focus 领域单元测试共享的内存排期状态仓，支持事务级原子回滚与失败注入。 */
class TestWorkSessionStore {
    val sessions = linkedMapOf<String, WorkSession>()
    val deletedSessionIds = linkedSetOf<String>()
    val tasks = linkedMapOf<String, Task>()
    val workspaces = linkedMapOf<String, Workspace>()
    val events = linkedMapOf<String, FixedEvent>()
    val focusSessions = linkedMapOf<String, FocusSession>()
    val focusSegments = linkedMapOf<String, FocusSegment>()

    var failNextSessionWrite = false
    var failNextFocusSessionWrite = false
    var failNextFocusSegmentWrite = false

    fun putTask(task: Task) {
        tasks[task.id] = task
    }

    fun putWorkspace(workspace: Workspace) {
        workspaces[workspace.id] = workspace
    }

    fun putEvent(event: FixedEvent) {
        events[event.id] = event
    }

    fun putWorkSession(session: WorkSession) {
        deletedSessionIds.remove(session.id)
        sessions[session.id] = session
    }

    fun putFocusSession(session: FocusSession) {
        focusSessions[session.id] = session
    }

    fun putFocusSegment(segment: FocusSegment) {
        focusSegments[segment.id] = segment
    }

    internal data class Snapshot(
        val sessions: LinkedHashMap<String, WorkSession>,
        val deletedSessionIds: LinkedHashSet<String>,
        val focusSessions: LinkedHashMap<String, FocusSession>,
        val focusSegments: LinkedHashMap<String, FocusSegment>,
    )

    internal fun capture(): Snapshot = Snapshot(
        sessions = LinkedHashMap(sessions),
        deletedSessionIds = LinkedHashSet(deletedSessionIds),
        focusSessions = LinkedHashMap(focusSessions),
        focusSegments = LinkedHashMap(focusSegments),
    )

    internal fun restore(snapshot: Snapshot) {
        sessions.clear()
        sessions.putAll(snapshot.sessions)
        deletedSessionIds.clear()
        deletedSessionIds.addAll(snapshot.deletedSessionIds)
        focusSessions.clear()
        focusSessions.putAll(snapshot.focusSessions)
        focusSegments.clear()
        focusSegments.putAll(snapshot.focusSegments)
    }

    internal fun interruptActiveFocusSessionsForPlan(workSessionId: String) {
        for ((id, focus) in focusSessions.entries.toList()) {
            if (focus.workSessionId == workSessionId &&
                (focus.status == FocusSessionStatus.ACTIVE || focus.status == FocusSessionStatus.PAUSED)
            ) {
                val latestEnd = focusSegments.values
                    .filter { it.sessionId == id }
                    .maxOfOrNull { it.endsAt }
                    ?: focus.startedAt
                focusSessions[id] = focus.copy(
                    status = FocusSessionStatus.INTERRUPTED,
                    endedAt = maxOf(focus.startedAt, latestEnd),
                )
            }
        }
    }
}

class TestWorkSessionRepository(
    val store: TestWorkSessionStore = TestWorkSessionStore(),
    override val ownerUserId: String = "00000000-0000-0000-0000-000000000001",
    private val revisionTracker: LocalDataRevision = LocalDataRevisionTracker(),
) : WorkSessionRepository {
    override val revision: StateFlow<Long> = revisionTracker.version

    override suspend fun findById(id: String): WorkSession? {
        SchedulingInput.requireId(id)
        return store.sessions[id]?.takeIf { it.ownerUserId == ownerUserId }
    }

    override suspend fun listByTask(taskId: String): List<WorkSession> {
        SchedulingInput.requireId(taskId)
        return store.sessions.values
            .filter { it.ownerUserId == ownerUserId && it.taskId == taskId }
            .sortedWith(compareBy(WorkSession::startsAt, WorkSession::id))
    }

    override suspend fun listInRange(start: Long, end: Long): List<WorkSession> {
        SchedulingInput.requireRange(start, end)
        return store.sessions.values
            .filter { it.ownerUserId == ownerUserId && it.startsAt < end && it.endsAt > start }
            .sortedWith(compareBy(WorkSession::startsAt, WorkSession::id))
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
        val task = store.tasks[id] ?: return null
        val workspaceId = task.workspaceId
        val accessible = task.createdBy == ownerUserId ||
            task.assigneeUserId == ownerUserId ||
            (workspaceId != null && findWorkspaceById(workspaceId) != null)
        return task.takeIf { accessible }
    }

    override suspend fun findWorkspaceById(id: String): Workspace? {
        SchedulingInput.requireId(id)
        return store.workspaces[id]?.takeIf { it.ownerUserId == ownerUserId }
    }

    override suspend fun listWorkspaces(status: WorkspaceStatus?): List<Workspace> =
        store.workspaces.values
            .filter { it.ownerUserId == ownerUserId && (status == null || it.status == status) }

    override suspend fun listFixedEventsInRange(start: Long, end: Long): List<FixedEvent> {
        SchedulingInput.requireRange(start, end)
        return store.events.values
            .filter { !it.isCancelled && it.createdBy == ownerUserId && it.startsAt < end && it.endsAt > start }
            .sortedWith(compareBy(FixedEvent::startsAt, FixedEvent::id))
    }

    override suspend fun listAllFixedEvents(): List<FixedEvent> =
        store.events.values
            .filter { !it.isCancelled && it.createdBy == ownerUserId }
            .sortedWith(compareBy(FixedEvent::startsAt, FixedEvent::id))

    override suspend fun listFixedEventOverridesIncludingDeleted(): List<FixedEvent> =
        store.events.values
            .filter { it.seriesId != null && it.createdBy == ownerUserId }

    override suspend fun listFocusSessions(workSessionId: String): List<FocusSession> {
        SchedulingInput.requireId(workSessionId)
        return store.focusSessions.values
            .filter { it.ownerUserId == ownerUserId && it.workSessionId == workSessionId }
            .sortedWith(compareBy(FocusSession::startedAt, FocusSession::id))
    }

    override suspend fun getFocusDuration(workSessionId: String): FocusDuration {
        SchedulingInput.requireId(workSessionId)
        val ownerSessionIds = store.focusSessions.values
            .filter { it.ownerUserId == ownerUserId && it.workSessionId == workSessionId }
            .map { it.id }
            .toSet()
        val segs = store.focusSegments.values.filter { it.sessionId in ownerSessionIds }
        return FocusDuration(
            foregroundMillis = segs.filter { it.mode == FocusMode.FOREGROUND }.sumOf { it.endsAt - it.startsAt },
            backgroundMillis = segs.filter { it.mode == FocusMode.BACKGROUND }.sumOf { it.endsAt - it.startsAt },
        )
    }

    override suspend fun <R> writeTransaction(block: suspend WorkSessionWriteScope.() -> R): R {
        val snapshot = store.capture()
        var mutated = false
        val scope = object : WorkSessionWriteScope {
            override val ownerUserId: String
                get() = this@TestWorkSessionRepository.ownerUserId

            override suspend fun findById(id: String): WorkSession? =
                this@TestWorkSessionRepository.findById(id)

            override suspend fun listByTask(taskId: String): List<WorkSession> =
                this@TestWorkSessionRepository.listByTask(taskId)

            override suspend fun listInRange(start: Long, end: Long): List<WorkSession> =
                this@TestWorkSessionRepository.listInRange(start, end)

            override suspend fun findTaskById(id: String): Task? =
                this@TestWorkSessionRepository.findTaskById(id)

            override suspend fun findWorkspaceById(id: String): Workspace? =
                this@TestWorkSessionRepository.findWorkspaceById(id)

            override suspend fun listAllFixedEvents(): List<FixedEvent> =
                this@TestWorkSessionRepository.listAllFixedEvents()

            override suspend fun listFixedEventOverridesIncludingDeleted(): List<FixedEvent> =
                this@TestWorkSessionRepository.listFixedEventOverridesIncludingDeleted()

            override suspend fun listFocusSessions(workSessionId: String): List<FocusSession> =
                this@TestWorkSessionRepository.listFocusSessions(workSessionId)

            override suspend fun getFocusDuration(workSessionId: String): FocusDuration =
                this@TestWorkSessionRepository.getFocusDuration(workSessionId)

            override suspend fun insertSessions(values: List<WorkSession>): List<WorkSession> {
                if (values.isEmpty()) return emptyList()
                if (store.failNextSessionWrite) {
                    store.failNextSessionWrite = false
                    error("模拟计划时段写入失败")
                }
                val checked = values.map {
                    SchedulingInput.workSession(it).also { s ->
                        require(s.ownerUserId == ownerUserId) { "只能保存本人计划时段" }
                        require(s.id !in store.sessions && s.id !in store.deletedSessionIds) { "重复的工作时段 ID" }
                        require(s.taskId == null || s.taskId in store.tasks) { "关联任务不存在或已删除" }
                    }
                }
                checked.forEach { store.sessions[it.id] = it }
                mutated = true
                return checked
            }

            override suspend fun replaceSession(value: WorkSession): Boolean {
                if (store.failNextSessionWrite) {
                    store.failNextSessionWrite = false
                    error("模拟计划时段更新失败")
                }
                val checked = SchedulingInput.workSession(value)
                require(checked.ownerUserId == ownerUserId) { "只能保存本人计划时段" }
                val existing = store.sessions[checked.id]?.takeIf { it.ownerUserId == ownerUserId } ?: return false
                require(checked.taskId == null || checked.taskId in store.tasks) { "关联任务不存在或已删除" }
                if (checked.status != WorkSessionStatus.PLANNED || checked.taskId == null) {
                    store.interruptActiveFocusSessionsForPlan(checked.id)
                }
                store.sessions[checked.id] = checked
                if (existing != checked) {
                    mutated = true
                }
                return true
            }

            override suspend fun removeSessionById(id: String): Boolean {
                SchedulingInput.requireId(id)
                if (store.failNextSessionWrite) {
                    store.failNextSessionWrite = false
                    error("模拟计划时段删除失败")
                }
                val existing = store.sessions[id]?.takeIf { it.ownerUserId == ownerUserId } ?: return false
                store.interruptActiveFocusSessionsForPlan(existing.id)
                store.sessions.remove(existing.id)
                store.deletedSessionIds.add(existing.id)
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
        } catch (t: Throwable) {
            store.restore(snapshot)
            throw t
        }
    }
}

class TestFocusRepository(
    val store: TestWorkSessionStore = TestWorkSessionStore(),
    override val ownerUserId: String = "00000000-0000-0000-0000-000000000001",
    private val revisionTracker: LocalDataRevision = LocalDataRevisionTracker(),
) : FocusRepository {
    override val revision: StateFlow<Long> = revisionTracker.version

    override suspend fun findSessionById(id: String): FocusSession? {
        SchedulingInput.requireId(id)
        return store.focusSessions[id]?.takeIf { it.ownerUserId == ownerUserId }
    }

    override suspend fun createSession(value: FocusSession): FocusSession =
        writeTransaction { insertSession(value) }

    override suspend fun updateSession(value: FocusSession): Boolean =
        writeTransaction { replaceSession(value) }

    override suspend fun listSessions(workSessionId: String): List<FocusSession> {
        SchedulingInput.requireId(workSessionId)
        return store.focusSessions.values
            .filter { it.ownerUserId == ownerUserId && it.workSessionId == workSessionId }
            .sortedWith(compareBy(FocusSession::startedAt, FocusSession::id))
    }

    override suspend fun appendSegment(value: FocusSegment): FocusSegment =
        writeTransaction { insertSegment(value) }

    override suspend fun listSegments(sessionId: String): List<FocusSegment> {
        if (findSessionById(sessionId) == null) return emptyList()
        return store.focusSegments.values
            .filter { it.sessionId == sessionId }
            .sortedWith(compareBy(FocusSegment::startsAt, FocusSegment::id))
    }

    override suspend fun getDuration(workSessionId: String): FocusDuration {
        SchedulingInput.requireId(workSessionId)
        val ownerSessionIds = store.focusSessions.values
            .filter { it.ownerUserId == ownerUserId && it.workSessionId == workSessionId }
            .map { it.id }
            .toSet()
        val segs = store.focusSegments.values.filter { it.sessionId in ownerSessionIds }
        return FocusDuration(
            foregroundMillis = segs.filter { it.mode == FocusMode.FOREGROUND }.sumOf { it.endsAt - it.startsAt },
            backgroundMillis = segs.filter { it.mode == FocusMode.BACKGROUND }.sumOf { it.endsAt - it.startsAt },
        )
    }

    override suspend fun <R> writeTransaction(block: suspend FocusWriteScope.() -> R): R {
        val snapshot = store.capture()
        var mutated = false
        val scope = object : FocusWriteScope {
            override val ownerUserId: String
                get() = this@TestFocusRepository.ownerUserId

            override suspend fun findSessionById(id: String): FocusSession? =
                this@TestFocusRepository.findSessionById(id)

            override suspend fun listSessions(workSessionId: String): List<FocusSession> =
                this@TestFocusRepository.listSessions(workSessionId)

            override suspend fun listSegments(sessionId: String): List<FocusSegment> =
                this@TestFocusRepository.listSegments(sessionId)

            override suspend fun getDuration(workSessionId: String): FocusDuration =
                this@TestFocusRepository.getDuration(workSessionId)

            override suspend fun findWorkSessionById(id: String): WorkSession? {
                SchedulingInput.requireId(id)
                return store.sessions[id]?.takeIf { it.ownerUserId == ownerUserId }
            }

            override suspend fun findWorkspaceById(id: String): Workspace? {
                SchedulingInput.requireId(id)
                return store.workspaces[id]?.takeIf { it.ownerUserId == ownerUserId }
            }

            override suspend fun findTaskById(id: String): Task? {
                SchedulingInput.requireId(id)
                val task = store.tasks[id] ?: return null
                val workspaceId = task.workspaceId
                val accessible = task.createdBy == ownerUserId ||
                    task.assigneeUserId == ownerUserId ||
                    (workspaceId != null && findWorkspaceById(workspaceId) != null)
                return task.takeIf { accessible }
            }

            override suspend fun updateWorkSession(session: WorkSession): Boolean {
                if (store.failNextSessionWrite) {
                    store.failNextSessionWrite = false
                    error("模拟计划时段更新失败")
                }
                val checked = SchedulingInput.workSession(session)
                require(checked.ownerUserId == ownerUserId) { "只能修改本人计划时段" }
                val existing = store.sessions[checked.id]?.takeIf { it.ownerUserId == ownerUserId } ?: return false
                store.sessions[checked.id] = checked
                if (existing != checked) {
                    mutated = true
                }
                return true
            }

            override suspend fun insertSession(value: FocusSession): FocusSession {
                if (store.failNextFocusSessionWrite) {
                    store.failNextFocusSessionWrite = false
                    error("模拟计时会话创建失败")
                }
                val checked = SchedulingInput.focusSession(value)
                require(checked.ownerUserId == ownerUserId) { "只能保存本人计时会话" }
                val plan = requireNotNull(store.sessions[checked.workSessionId]) { "计划时段不存在或已删除" }
                require(plan.ownerUserId == ownerUserId) { "计时会话必须属于计划时段所有者" }
                require(plan.taskId != null && plan.status == WorkSessionStatus.PLANNED) { "只能为关联任务的计划中时段开启计时" }
                if (checked.status == FocusSessionStatus.ACTIVE || checked.status == FocusSessionStatus.PAUSED) {
                    val hasActive = store.focusSessions.values.any {
                        it.ownerUserId == ownerUserId &&
                            it.id != checked.id &&
                            (it.status == FocusSessionStatus.ACTIVE || it.status == FocusSessionStatus.PAUSED)
                    }
                    require(!hasActive) { "本人已有运行或暂停的计时会话" }
                }
                store.focusSessions[checked.id] = checked
                mutated = true
                return checked
            }

            override suspend fun replaceSession(value: FocusSession): Boolean {
                if (store.failNextFocusSessionWrite) {
                    store.failNextFocusSessionWrite = false
                    error("模拟计时会话更新失败")
                }
                val checked = SchedulingInput.focusSession(value)
                require(checked.ownerUserId == ownerUserId) { "只能修改本人计时会话" }
                val old = store.focusSessions[checked.id]?.takeIf { it.ownerUserId == ownerUserId } ?: return false
                require(checked.workSessionId == old.workSessionId && checked.startedAt == old.startedAt) {
                    "不能转移计时会话或修改其开始时间"
                }
                if (old.status == FocusSessionStatus.ENDED || old.status == FocusSessionStatus.INTERRUPTED) {
                    require(checked.status == old.status && checked.endedAt == old.endedAt) {
                        "已结束的计时会话不能重开或改写结束时间"
                    }
                }
                val latestEnd = store.focusSegments.values
                    .filter { it.sessionId == checked.id }
                    .maxOfOrNull { it.endsAt }
                require(checked.endedAt == null || latestEnd == null || checked.endedAt >= latestEnd) {
                    "会话结束不能早于已确认片段"
                }
                store.focusSessions[checked.id] = checked
                if (old != checked) {
                    mutated = true
                }
                return true
            }

            override suspend fun insertSegment(value: FocusSegment): FocusSegment {
                if (store.failNextFocusSegmentWrite) {
                    store.failNextFocusSegmentWrite = false
                    error("模拟计时片段写入失败")
                }
                val checked = SchedulingInput.segment(value)
                val session = requireNotNull(findSessionById(checked.sessionId)) { "计时会话不存在或不属于本人" }
                val existing = store.focusSegments.values.firstOrNull {
                    it.sessionId == checked.sessionId && it.clientSegmentId == checked.clientSegmentId
                }
                if (existing != null) {
                    require(existing == checked.copy(id = existing.id)) { "同一计时片段提交标识不能对应不同内容" }
                    return existing
                }
                require(checked.startsAt >= session.startedAt && (session.endedAt == null || checked.endsAt <= session.endedAt)) {
                    "有效片段必须位于实际计时会话内"
                }
                val ownerSessionIds = store.focusSessions.values
                    .filter { it.ownerUserId == ownerUserId }
                    .map { it.id }
                    .toSet()
                val overlaps = store.focusSegments.values.any {
                    it.sessionId in ownerSessionIds &&
                        it.startsAt < checked.endsAt &&
                        checked.startsAt < it.endsAt
                }
                require(!overlaps) { "本人已确认的有效计时不能重叠" }
                store.focusSegments[checked.id] = checked
                mutated = true
                return checked
            }
        }
        return try {
            val result = scope.block()
            if (mutated) {
                revisionTracker.notifyChanged()
            }
            result
        } catch (t: Throwable) {
            store.restore(snapshot)
            throw t
        }
    }
}
