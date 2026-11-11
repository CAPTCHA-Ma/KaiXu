package com.kaixu.data.repository

import com.kaixu.data.datasource.*
import com.kaixu.domain.model.scheduling.*
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.repository.SchedulingTransaction
import com.kaixu.domain.repository.WorkSessionRepository
import com.kaixu.domain.validation.scheduling.SchedulingInput

/** 不持有独立缓存，避免任务删除或其他业务写入后产生过时日程。 */
class DefaultWorkSessionRepository(
    private val source: WorkSessionDataSource,
    private val tasks: TaskDataSource,
    private val events: FixedEventDataSource,
    private val workspaces: WorkspaceDataSource,
    override val ownerUserId: String,
    private val transaction: SchedulingTransaction,
) : WorkSessionRepository {
    init { SchedulingInput.requireId(ownerUserId) }

    override suspend fun findById(id: String): WorkSession? {
        SchedulingInput.requireId(id)
        return source.findWorkSessionById(id)?.takeIf { it.ownerUserId == ownerUserId }
    }

    override suspend fun listByTask(taskId: String): List<WorkSession> {
        SchedulingInput.requireId(taskId)
        return source.listWorkSessionsByOwnerAndTask(ownerUserId, taskId)
            .filter { it.ownerUserId == ownerUserId && it.taskId == taskId }.sortedByTime()
    }

    override suspend fun listInRange(start: Long, end: Long): List<WorkSession> {
        SchedulingInput.requireRange(start, end)
        return source.listWorkSessionsInRange(ownerUserId, start, end)
            .filter { it.ownerUserId == ownerUserId && it.startsAt < end && it.endsAt > start }.sortedByTime()
    }

    override suspend fun createAll(values: List<WorkSession>): List<WorkSession> {
        val checked = values.map { SchedulingInput.workSession(it).also(::requireOwner) }
        return source.createWorkSessions(checked)
    }

    override suspend fun update(value: WorkSession): Boolean = transaction.run {
        val checked = SchedulingInput.workSession(value).also(::requireOwner)
        if (findById(checked.id) == null) false else source.updateWorkSession(checked)
    }

    override suspend fun deleteById(id: String): Boolean = transaction.run {
        if (findById(id) == null) false else source.deleteWorkSessionById(id)
    }

    override suspend fun findTaskById(id: String): Task? {
        val task = tasks.findTaskById(id.also(SchedulingInput::requireId)) ?: return null
        val workspaceId = task.workspaceId
        return task.takeIf {
            if (workspaceId == null) it.createdBy == ownerUserId else findWorkspaceById(workspaceId) != null
        }
    }
    override suspend fun findWorkspaceById(id: String) = workspaces.findWorkspaceById(id.also(SchedulingInput::requireId))
        ?.takeIf { it.ownerUserId == ownerUserId }

    override suspend fun listFixedEventsInRange(start: Long, end: Long): List<FixedEvent> {
        SchedulingInput.requireRange(start, end)
        return events.listEventsInRange(ownerUserId, start, end)
            .filter { it.createdBy == ownerUserId && it.startsAt < end && it.endsAt > start }
            .sortedWith(compareBy(FixedEvent::startsAt, FixedEvent::id))
    }

    private fun requireOwner(value: WorkSession) = require(value.ownerUserId == ownerUserId) { "只能保存本人计划时段" }
    private fun List<WorkSession>.sortedByTime() = sortedWith(compareBy(WorkSession::startsAt, WorkSession::id))
}
