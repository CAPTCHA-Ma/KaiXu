package com.kaixu.data.repository

import com.kaixu.data.datasource.FocusDataSource
import com.kaixu.data.datasource.TaskDataSource
import com.kaixu.data.datasource.WorkSessionDataSource
import com.kaixu.data.datasource.WorkspaceDataSource
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.domain.model.scheduling.FocusDuration
import com.kaixu.domain.model.scheduling.FocusSegment
import com.kaixu.domain.model.scheduling.FocusSession
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.repository.DirectTransaction
import com.kaixu.domain.repository.FocusRepository
import com.kaixu.domain.repository.FocusWriteScope
import com.kaixu.domain.repository.LocalDataRevision
import com.kaixu.domain.repository.LocalDataRevisionTracker
import com.kaixu.domain.repository.LocalTransaction
import com.kaixu.domain.validation.scheduling.SchedulingInput
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 协调本人专注计时会话、有效片段及关联计划时段上下文。
 * 纯读操作不加互斥锁以避免事务内重入死锁；写事务成功提交且实际修改数据后递增修订号。
 */
class DefaultFocusRepository(
    private val source: FocusDataSource,
    override val ownerUserId: String = LOCAL_ACTOR_ID,
    private val transaction: LocalTransaction = DirectTransaction,
    private val revisionTracker: LocalDataRevision = LocalDataRevisionTracker(),
    private val workSessions: WorkSessionDataSource? = null,
    private val tasks: TaskDataSource? = null,
    private val workspaces: WorkspaceDataSource? = null,
) : FocusRepository {
    init {
        SchedulingInput.requireId(ownerUserId)
    }

    override val revision: StateFlow<Long> = revisionTracker.version
    private val mutex = Mutex()

    override suspend fun findSessionById(id: String): FocusSession? {
        SchedulingInput.requireId(id)
        return source.findFocusSessionById(id)?.takeIf { it.ownerUserId == ownerUserId }
    }

    override suspend fun createSession(value: FocusSession): FocusSession =
        writeTransaction { insertSession(value) }

    override suspend fun updateSession(value: FocusSession): Boolean =
        writeTransaction { replaceSession(value) }

    override suspend fun listSessions(workSessionId: String): List<FocusSession> {
        SchedulingInput.requireId(workSessionId)
        return source.listFocusSessions(ownerUserId, workSessionId)
            .filter { it.ownerUserId == ownerUserId && it.workSessionId == workSessionId }
            .sortedWith(compareBy(FocusSession::startedAt, FocusSession::id))
    }

    override suspend fun appendSegment(value: FocusSegment): FocusSegment =
        writeTransaction { insertSegment(value) }

    override suspend fun listSegments(sessionId: String): List<FocusSegment> {
        if (findSessionById(sessionId) == null) return emptyList()
        return source.listFocusSegments(sessionId)
            .filter { it.sessionId == sessionId }
            .sortedWith(compareBy(FocusSegment::startsAt, FocusSegment::id))
    }

    override suspend fun getDuration(workSessionId: String): FocusDuration {
        SchedulingInput.requireId(workSessionId)
        // source 以 owner + 计划时段查询，软删除计划后仍允许读取本人计时历史。
        return source.getFocusDuration(ownerUserId, workSessionId)
    }

    private suspend fun findOwnerWorkSession(id: String): WorkSession? {
        SchedulingInput.requireId(id)
        return workSessions?.findWorkSessionById(id)?.takeIf { it.ownerUserId == ownerUserId }
    }

    private suspend fun findOwnerWorkspace(id: String): Workspace? {
        SchedulingInput.requireId(id)
        return workspaces?.findWorkspaceById(id)?.takeIf { it.ownerUserId == ownerUserId }
    }

    private suspend fun findAccessibleTask(id: String): Task? {
        SchedulingInput.requireId(id)
        val task = tasks?.findTaskById(id) ?: return null
        val workspaceId = task.workspaceId
        val accessible = task.createdBy == ownerUserId ||
            task.assigneeUserId == ownerUserId ||
            (workspaceId != null && findOwnerWorkspace(workspaceId) != null)
        return task.takeIf { accessible }
    }

    override suspend fun <R> writeTransaction(block: suspend FocusWriteScope.() -> R): R = mutex.withLock {
        var mutated = false
        val scope = object : FocusWriteScope {
            override val ownerUserId: String
                get() = this@DefaultFocusRepository.ownerUserId

            override suspend fun findSessionById(id: String): FocusSession? =
                this@DefaultFocusRepository.findSessionById(id)

            override suspend fun listSessions(workSessionId: String): List<FocusSession> =
                this@DefaultFocusRepository.listSessions(workSessionId)

            override suspend fun listSegments(sessionId: String): List<FocusSegment> =
                this@DefaultFocusRepository.listSegments(sessionId)

            override suspend fun getDuration(workSessionId: String): FocusDuration =
                this@DefaultFocusRepository.getDuration(workSessionId)

            override suspend fun findWorkSessionById(id: String): WorkSession? =
                findOwnerWorkSession(id)

            override suspend fun findTaskById(id: String): Task? =
                findAccessibleTask(id)

            override suspend fun findWorkspaceById(id: String): Workspace? =
                findOwnerWorkspace(id)

            override suspend fun updateWorkSession(session: WorkSession): Boolean {
                val checked = SchedulingInput.workSession(session)
                require(checked.ownerUserId == ownerUserId) { "只能修改本人计划时段" }
                val sessionSource = checkNotNull(workSessions) { "当前计时仓储未接入工作时段数据源" }
                val existing = sessionSource.findWorkSessionById(checked.id)
                    ?.takeIf { it.ownerUserId == ownerUserId }
                    ?: return false
                val updated = sessionSource.updateWorkSession(checked)
                if (updated && existing != checked) {
                    mutated = true
                }
                return updated
            }

            override suspend fun insertSession(value: FocusSession): FocusSession {
                val checked = SchedulingInput.focusSession(value)
                require(checked.ownerUserId == ownerUserId) { "只能保存本人计时会话" }
                val saved = source.createFocusSession(checked)
                mutated = true
                return saved
            }

            override suspend fun replaceSession(value: FocusSession): Boolean {
                val checked = SchedulingInput.focusSession(value)
                require(checked.ownerUserId == ownerUserId) { "只能修改本人计时会话" }
                val existing = findSessionById(checked.id) ?: return false
                val updated = source.updateFocusSession(checked)
                if (updated && existing != checked) {
                    mutated = true
                }
                return updated
            }

            override suspend fun insertSegment(value: FocusSegment): FocusSegment {
                val checked = SchedulingInput.segment(value)
                requireNotNull(findSessionById(checked.sessionId)) { "计时会话不存在或不属于本人" }
                val alreadySubmitted = source.listFocusSegments(checked.sessionId)
                    .any { it.clientSegmentId == checked.clientSegmentId }
                val saved = source.appendFocusSegment(checked)
                if (!alreadySubmitted) {
                    mutated = true
                }
                return saved
            }
        }

        val result = transaction.run { scope.block() }
        if (mutated) {
            revisionTracker.notifyChanged()
        }
        result
    }
}
