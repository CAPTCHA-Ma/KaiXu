package com.kaixu.domain.usecase.tasks

import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.model.tasks.CompleteTaskResult
import com.kaixu.domain.model.tasks.FutureSessionPolicy
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.tasks.TaskDraft
import com.kaixu.domain.model.tasks.TaskStatus
import com.kaixu.domain.model.tasks.toDraft
import com.kaixu.domain.model.tasks.withDeadline
import com.kaixu.domain.repository.TaskRepository
import com.kaixu.domain.repository.TaskWriteScope
import com.kaixu.domain.validation.tasks.TaskInput

/** 创建任务：快速标题录入与完整表单均经此校验工作区归属并在事务内写入。 */
class CreateTaskUseCase(private val repository: TaskRepository) {
    suspend operator fun invoke(draft: TaskDraft, id: String? = null): Task {
        val candidate = TaskInput.toNewTask(draft = draft, createdBy = repository.ownerUserId, id = id)
        return repository.writeTransaction {
            requireActiveTargetWorkspace(candidate.workspaceId)
            insertTask(candidate)
        }
    }

    suspend operator fun invoke(title: String): Task = invoke(TaskDraft(title = title))
}

/**
 * 编辑任务完整业务字段，支持直接覆盖更新及基于基准记录的字段级冲突合并。
 * 完成或恢复未完成的状态变更分别由 [CompleteTaskUseCase] 与 [ReopenTaskUseCase] 处理。
 */
class UpdateTaskUseCase(private val repository: TaskRepository) {
    suspend operator fun invoke(
        taskId: String,
        draft: TaskDraft,
        nextStatus: TaskStatus? = null,
        expectedOriginal: Task? = null,
    ): Task {
        TaskInput.requireId(taskId)
        if (expectedOriginal != null) {
            require(expectedOriginal.id == taskId) { "基准任务 ID 与目标任务不一致" }
        }
        val cleanDraft = TaskInput.normalizedDraft(draft)
        return repository.writeTransaction {
            val latest = checkNotNull(findTaskById(taskId)) { "任务已不可用，请刷新列表后重试" }
            requireEditableTask(latest)
            val proposed = if (expectedOriginal == null) {
                if (cleanDraft.workspaceId != latest.workspaceId) {
                    requireActiveTargetWorkspace(cleanDraft.workspaceId)
                }
                TaskInput.applyDraft(
                    existing = latest,
                    draft = cleanDraft,
                    nextStatus = nextStatus ?: latest.status,
                )
            } else {
                mergeAgainstOriginal(latest, expectedOriginal, cleanDraft, nextStatus)
            }
            check(replaceTask(proposed)) { "任务已不可用，请刷新列表后重试" }
            proposed
        }
    }

    private suspend fun TaskWriteScope.mergeAgainstOriginal(
        latest: Task,
        original: Task,
        cleanDraft: TaskDraft,
        nextStatus: TaskStatus?,
    ): Task {
        require(latest.createdBy == original.createdBy) { "任务归属已变化，请关闭后重新打开" }
        val origDraft = normalizeExistingDraft(original)
        val latestDraft = normalizeExistingDraft(latest)

        val resolvedTitle = resolveField(cleanDraft.title, origDraft.title, latestDraft.title, "任务标题")
        val resolvedDescription = resolveField(
            cleanDraft.description,
            origDraft.description,
            latestDraft.description,
            "任务说明",
        )
        val resolvedWorkspaceId = resolveField(
            cleanDraft.workspaceId,
            origDraft.workspaceId,
            latestDraft.workspaceId,
            "所属工作区",
        )
        if (resolvedWorkspaceId != origDraft.workspaceId) {
            requireActiveTargetWorkspace(resolvedWorkspaceId)
        }
        val resolvedPriority = resolveField(
            cleanDraft.priority,
            origDraft.priority,
            latestDraft.priority,
            "任务优先级",
        )
        val resolvedDeadline = resolveField(
            cleanDraft.deadline,
            origDraft.deadline,
            latestDraft.deadline,
            "任务截止要求",
        )
        val resolvedEstimated = resolveField(
            cleanDraft.estimatedMinutes,
            origDraft.estimatedMinutes,
            latestDraft.estimatedMinutes,
            "预计时长",
        )
        val resolvedBlockedNote = resolveField(
            cleanDraft.blockedNote,
            origDraft.blockedNote,
            latestDraft.blockedNote,
            "阻塞说明",
        )

        val requestedStatus = nextStatus ?: original.status
        val statusChanged = requestedStatus != original.status
        if (statusChanged) {
            require(original.status != TaskStatus.COMPLETED && requestedStatus != TaskStatus.COMPLETED) {
                "完成状态请通过任务完成流程处理"
            }
            require(latest.status == original.status || latest.status == requestedStatus) {
                "任务状态已变化，请刷新后重试"
            }
        }
        val resolvedStatus = if (statusChanged) requestedStatus else latest.status

        return TaskInput.normalized(
            latest.copy(
                title = resolvedTitle,
                description = resolvedDescription,
                workspaceId = resolvedWorkspaceId,
                priority = resolvedPriority,
                estimatedMinutes = resolvedEstimated,
                blockedNote = resolvedBlockedNote,
                status = resolvedStatus,
            ).withDeadline(resolvedDeadline),
        )
    }

    private fun normalizeExistingDraft(task: Task): TaskDraft {
        val raw = task.toDraft()
        return raw.copy(
            title = raw.title.trim(),
            description = raw.description?.trim()?.ifEmpty { null },
            blockedNote = raw.blockedNote?.trim()?.ifEmpty { null },
        )
    }

    private fun <T> resolveField(draftValue: T, originalValue: T, latestValue: T, label: String): T {
        if (draftValue == originalValue) return latestValue
        require(latestValue == originalValue || latestValue == draftValue) {
            "${label}已被修改，请刷新后重试"
        }
        return draftValue
    }
}

/**
 * 完成任务并在同一本地事务内处理本人未来计划时段（UC11）：
 * 无未来计划时段直接完成；存在未来计划时段且未指定策略时先返回确认快照。
 */
class CompleteTaskUseCase(private val repository: TaskRepository) {
    suspend operator fun invoke(
        taskId: String,
        nowMillis: Long = currentEpochMillis(),
        policy: FutureSessionPolicy? = null,
    ): CompleteTaskResult {
        TaskInput.requireId(taskId)
        return repository.writeTransaction {
            val current = checkNotNull(findTaskById(taskId)) { "任务不存在或已删除" }
            requireEditableTask(current)
            if (current.status == TaskStatus.COMPLETED) {
                return@writeTransaction CompleteTaskResult.Completed(task = current)
            }
            val futureSessions = listOwnerWorkSessionsByTask(current.id)
                .filter { it.status == WorkSessionStatus.PLANNED && it.startsAt > nowMillis }
            if (futureSessions.isNotEmpty() && policy == null) {
                return@writeTransaction CompleteTaskResult.NeedsFutureSessionDecision(
                    task = current,
                    futureSessions = futureSessions,
                )
            }
            val completedTask = TaskInput.normalized(
                current.copy(status = TaskStatus.COMPLETED, completedAt = nowMillis),
            )
            check(replaceTask(completedTask)) { "任务完成状态保存失败" }
            when (policy) {
                FutureSessionPolicy.CANCEL_FUTURE -> {
                    for (session in futureSessions) {
                        check(updateOwnerWorkSession(session.copy(status = WorkSessionStatus.CANCELLED))) {
                            "取消未来工作时段失败"
                        }
                    }
                    CompleteTaskResult.Completed(
                        task = completedTask,
                        retainedFutureCount = 0,
                        cancelledFutureCount = futureSessions.size,
                    )
                }
                FutureSessionPolicy.KEEP_FUTURE, null -> CompleteTaskResult.Completed(
                    task = completedTask,
                    retainedFutureCount = futureSessions.size,
                    cancelledFutureCount = 0,
                )
            }
        }
    }
}

/** 将已完成任务恢复为未完成状态，不自动重建已取消的工作时段。 */
class ReopenTaskUseCase(private val repository: TaskRepository) {
    suspend operator fun invoke(
        taskId: String,
        targetStatus: TaskStatus = TaskStatus.TODO,
    ): Task {
        TaskInput.requireId(taskId)
        require(targetStatus == TaskStatus.TODO || targetStatus == TaskStatus.IN_PROGRESS) {
            "恢复未完成的目标状态只能是待办或进行中"
        }
        return repository.writeTransaction {
            val current = checkNotNull(findTaskById(taskId)) { "任务不存在或已删除" }
            requireEditableTask(current)
            if (current.status == targetStatus && current.completedAt == null) {
                return@writeTransaction current
            }
            val reopened = TaskInput.normalized(
                current.copy(status = targetStatus, completedAt = null),
            )
            check(replaceTask(reopened)) { "恢复任务状态失败" }
            reopened
        }
    }
}

/** 校验本人与工作区可写权限后软删除任务，并在本地事务中脱链工作时段、保留历史计时。 */
class DeleteTaskUseCase(private val repository: TaskRepository) {
    suspend operator fun invoke(taskId: String): Boolean {
        TaskInput.requireId(taskId)
        return repository.writeTransaction {
            val current = findTaskById(taskId) ?: return@writeTransaction false
            requireEditableTask(current)
            removeTaskById(current.id)
        }
    }
}
