package com.kaixu.testing

import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.repository.LocalDataRevision
import com.kaixu.domain.repository.LocalDataRevisionTracker
import com.kaixu.domain.repository.TaskRepository
import com.kaixu.domain.repository.TaskWriteScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 仅用于领域与 ViewModel 单元测试；控制延迟/失败，不进入正式客户端。 */
class TestTaskRepository(
    private val latencyMillis: Long = 0,
    override val ownerUserId: String = "00000000-0000-0000-0000-000000000001",
    private val revisionTracker: LocalDataRevision = LocalDataRevisionTracker(),
) : TaskRepository {
    private val records = MutableStateFlow<List<Task>>(emptyList())
    override val tasks: StateFlow<List<Task>> = records.asStateFlow()
    override val revision: StateFlow<Long> = revisionTracker.version

    private val workspaces = linkedMapOf<String, Workspace>()
    private val workSessions = linkedMapOf<String, WorkSession>()
    private var failNext = false
    private var failRefresh = false

    fun failNextCreate() { failNext = true }
    fun failNextRefresh() { failRefresh = true }

    fun putWorkspace(workspace: Workspace) {
        workspaces[workspace.id] = workspace
    }

    fun putWorkSession(session: WorkSession) {
        workSessions[session.id] = session
    }

    fun storedWorkSession(id: String): WorkSession? = workSessions[id]

    override suspend fun refresh() {
        if (failRefresh) {
            failRefresh = false
            error("测试读取失败")
        }
    }

    override suspend fun listAll(): List<Task> = records.value

    override suspend fun create(task: Task): Task = writeTransaction { insertTask(task) }

    override suspend fun update(task: Task): Boolean = writeTransaction { replaceTask(task) }

    override suspend fun deleteById(id: String): Boolean = writeTransaction { removeTaskById(id) }

    override suspend fun findById(id: String): Task? = records.value.find { it.id == id }

    override suspend fun listByWorkspace(workspaceId: String): List<Task> =
        records.value.filter { it.workspaceId == workspaceId }

    override suspend fun search(keyword: String): List<Task> {
        val query = keyword.trim()
        return records.value.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.description?.contains(query, ignoreCase = true) == true
        }
    }

    override suspend fun findWorkspaceById(id: String): Workspace? =
        workspaces[id]?.takeIf { it.ownerUserId == ownerUserId }

    override suspend fun listWorkspaces(status: WorkspaceStatus?): List<Workspace> =
        workspaces.values.filter { it.ownerUserId == ownerUserId && (status == null || it.status == status) }

    override suspend fun listOwnerWorkSessions(): List<WorkSession> =
        workSessions.values
            .filter { it.ownerUserId == ownerUserId }
            .sortedWith(compareBy(WorkSession::startsAt, WorkSession::id))

    override suspend fun listOwnerWorkSessionsByTask(taskId: String): List<WorkSession> =
        workSessions.values
            .filter { it.ownerUserId == ownerUserId && it.taskId == taskId }
            .sortedWith(compareBy(WorkSession::startsAt, WorkSession::id))

    override suspend fun <R> writeTransaction(block: suspend TaskWriteScope.() -> R): R {
        val snapshotTasks = records.value
        val snapshotSessions = workSessions.toMap()
        var mutated = false
        val scope = object : TaskWriteScope {
            override val ownerUserId: String
                get() = this@TestTaskRepository.ownerUserId

            override suspend fun findTaskById(id: String): Task? =
                this@TestTaskRepository.findById(id)

            override suspend fun findWorkspaceById(id: String): Workspace? =
                this@TestTaskRepository.findWorkspaceById(id)

            override suspend fun listOwnerWorkSessionsByTask(taskId: String): List<WorkSession> =
                this@TestTaskRepository.listOwnerWorkSessionsByTask(taskId)

            override suspend fun insertTask(task: Task): Task {
                delay(latencyMillis)
                if (failNext) {
                    failNext = false
                    error("测试保存失败")
                }
                records.value = listOf(task) + records.value.filterNot { it.id == task.id }
                mutated = true
                return task
            }

            override suspend fun replaceTask(task: Task): Boolean {
                if (records.value.none { it.id == task.id }) return false
                records.value = records.value.map { if (it.id == task.id) task else it }
                mutated = true
                return true
            }

            override suspend fun removeTaskById(id: String): Boolean {
                val found = records.value.any { it.id == id }
                if (found) {
                    records.value = records.value.filterNot { it.id == id }
                    workSessions.entries.toList().forEach { (sessionId, session) ->
                        if (session.taskId == id) {
                            workSessions[sessionId] = session.copy(
                                taskId = null,
                                stageId = null,
                                detachedAt = 1L,
                                detachReason = "TASK_DELETED",
                            )
                        }
                    }
                    mutated = true
                }
                return found
            }

            override suspend fun updateOwnerWorkSession(session: WorkSession): Boolean {
                val existing = workSessions[session.id] ?: return false
                if (existing.ownerUserId != ownerUserId || session.ownerUserId != ownerUserId) return false
                workSessions[session.id] = session
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
            records.value = snapshotTasks
            workSessions.clear()
            workSessions.putAll(snapshotSessions)
            throw failure
        }
    }
}
