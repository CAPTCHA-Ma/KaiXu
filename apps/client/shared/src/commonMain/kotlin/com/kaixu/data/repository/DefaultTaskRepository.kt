package com.kaixu.data.repository

import com.kaixu.data.datasource.TaskDataSource
import com.kaixu.data.datasource.WorkSessionDataSource
import com.kaixu.data.datasource.WorkspaceDataSource
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.repository.DirectSchedulingTransaction
import com.kaixu.domain.repository.LocalDataRevision
import com.kaixu.domain.repository.LocalDataRevisionTracker
import com.kaixu.domain.repository.SchedulingTransaction
import com.kaixu.domain.repository.TaskRepository
import com.kaixu.domain.repository.TaskWriteScope
import com.kaixu.domain.validation.scheduling.SchedulingInput
import com.kaixu.domain.validation.tasks.TaskInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private sealed interface StagedTaskMutation {
    data class Insert(val task: Task) : StagedTaskMutation
    data class Replace(val task: Task) : StagedTaskMutation
    data class Delete(val id: String) : StagedTaskMutation
}

/** Model 数据协调：成功提交后才发布新列表并递增修订号，失败不伪装成保存成功。 */
class DefaultTaskRepository(
    private val source: TaskDataSource,
    private val workspaces: WorkspaceDataSource? = null,
    private val workSessions: WorkSessionDataSource? = null,
    override val ownerUserId: String = LOCAL_ACTOR_ID,
    private val transaction: SchedulingTransaction = DirectSchedulingTransaction,
    private val revisionTracker: LocalDataRevision = LocalDataRevisionTracker(),
) : TaskRepository {
    init {
        TaskInput.requireId(ownerUserId)
    }

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    override val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()
    override val revision: StateFlow<Long> = revisionTracker.version
    private val mutex = Mutex()

    override suspend fun refresh() = mutex.withLock {
        val loaded = source.listTasks()
        val changed = _tasks.value != loaded
        _tasks.value = loaded
        if (changed) {
            revisionTracker.notifyChanged()
        }
    }

    override suspend fun listAll(): List<Task> = source.listTasks()

    override suspend fun create(task: Task): Task {
        val clean = TaskInput.normalized(task)
        return writeTransaction { insertTask(clean) }
    }

    override suspend fun update(task: Task): Boolean {
        val clean = TaskInput.normalized(task)
        return writeTransaction { replaceTask(clean) }
    }

    override suspend fun deleteById(id: String): Boolean {
        TaskInput.requireId(id)
        return writeTransaction { removeTaskById(id) }
    }

    override suspend fun findById(id: String): Task? {
        TaskInput.requireId(id)
        return source.findTaskById(id)
    }

    override suspend fun listByWorkspace(workspaceId: String): List<Task> {
        TaskInput.requireId(workspaceId)
        return source.listTasksByWorkspace(workspaceId)
    }

    override suspend fun search(keyword: String): List<Task> {
        val query = keyword.trim()
        return source.listTasks().filter {
            it.title.contains(query, ignoreCase = true) ||
                it.description?.contains(query, ignoreCase = true) == true
        }
    }

    override suspend fun findWorkspaceById(id: String): Workspace? {
        TaskInput.requireId(id)
        return workspaces?.findWorkspaceById(id)?.takeIf { it.ownerUserId == ownerUserId }
    }

    override suspend fun listWorkspaces(status: WorkspaceStatus?): List<Workspace> =
        workspaces?.listWorkspaces(status)
            ?.filter { it.ownerUserId == ownerUserId }
            .orEmpty()

    override suspend fun listOwnerWorkSessions(): List<WorkSession> =
        workSessions?.listWorkSessions()
            ?.filter { it.ownerUserId == ownerUserId }
            ?.sortedWith(compareBy(WorkSession::startsAt, WorkSession::id))
            .orEmpty()

    override suspend fun listOwnerWorkSessionsByTask(taskId: String): List<WorkSession> {
        TaskInput.requireId(taskId)
        return workSessions?.listWorkSessionsByOwnerAndTask(ownerUserId, taskId)
            ?.filter { it.ownerUserId == ownerUserId && it.taskId == taskId }
            ?.sortedWith(compareBy(WorkSession::startsAt, WorkSession::id))
            .orEmpty()
    }

    override suspend fun <R> writeTransaction(block: suspend TaskWriteScope.() -> R): R = mutex.withLock {
        val stagedMutations = mutableListOf<StagedTaskMutation>()
        var mutated = false
        val scope = object : TaskWriteScope {
            override val ownerUserId: String
                get() = this@DefaultTaskRepository.ownerUserId

            override suspend fun findTaskById(id: String): Task? =
                this@DefaultTaskRepository.findById(id)

            override suspend fun findWorkspaceById(id: String): Workspace? =
                this@DefaultTaskRepository.findWorkspaceById(id)

            override suspend fun listOwnerWorkSessionsByTask(taskId: String): List<WorkSession> =
                this@DefaultTaskRepository.listOwnerWorkSessionsByTask(taskId)

            override suspend fun insertTask(task: Task): Task {
                val clean = TaskInput.normalized(task)
                val saved = source.createTask(clean)
                stagedMutations += StagedTaskMutation.Insert(saved)
                mutated = true
                return saved
            }

            override suspend fun replaceTask(task: Task): Boolean {
                val clean = TaskInput.normalized(task)
                val updated = source.updateTask(clean)
                if (updated) {
                    stagedMutations += StagedTaskMutation.Replace(clean)
                    mutated = true
                }
                return updated
            }

            override suspend fun removeTaskById(id: String): Boolean {
                TaskInput.requireId(id)
                val deleted = source.deleteTaskById(id)
                if (deleted) {
                    stagedMutations += StagedTaskMutation.Delete(id)
                    mutated = true
                }
                return deleted
            }

            override suspend fun updateOwnerWorkSession(session: WorkSession): Boolean {
                val checked = SchedulingInput.workSession(session)
                require(checked.ownerUserId == ownerUserId) { "只能修改本人计划时段" }
                val sessionSource = checkNotNull(workSessions) { "当前任务仓储未接入工作时段数据源" }
                if (sessionSource.findWorkSessionById(checked.id)?.ownerUserId != ownerUserId) return false
                val updated = sessionSource.updateWorkSession(checked)
                if (updated) mutated = true
                return updated
            }
        }

        val result = transaction.run { scope.block() }

        if (stagedMutations.isNotEmpty()) {
            var next = _tasks.value
            for (mutation in stagedMutations) {
                next = when (mutation) {
                    is StagedTaskMutation.Insert ->
                        listOf(mutation.task) + next.filterNot { it.id == mutation.task.id }
                    is StagedTaskMutation.Replace ->
                        if (next.any { it.id == mutation.task.id }) {
                            next.map { if (it.id == mutation.task.id) mutation.task else it }
                        } else {
                            listOf(mutation.task) + next
                        }
                    is StagedTaskMutation.Delete ->
                        next.filterNot { it.id == mutation.id }
                }
            }
            _tasks.value = next
        }
        if (mutated) {
            revisionTracker.notifyChanged()
        }
        result
    }
}

