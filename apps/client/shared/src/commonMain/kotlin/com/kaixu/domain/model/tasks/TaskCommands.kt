package com.kaixu.domain.model.tasks

import com.kaixu.domain.model.scheduling.WorkSession

/** 新建或编辑任务的业务输入；不包含创建者、完成时间等受控字段。 */
data class TaskDraft(
    val title: String,
    val description: String? = null,
    val workspaceId: String? = null,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val deadline: TaskDeadline = TaskDeadline.None,
    val estimatedMinutes: Int? = null,
    val blockedNote: String? = null,
)

fun Task.toDraft(): TaskDraft = TaskDraft(
    title = title,
    description = description,
    workspaceId = workspaceId,
    priority = priority,
    deadline = deadline,
    estimatedMinutes = estimatedMinutes,
    blockedNote = blockedNote,
)

/** 完成任务时对操作者本人未来计划中（PLANNED）工作时段的处理策略。 */
enum class FutureSessionPolicy {
    KEEP_FUTURE,
    CANCEL_FUTURE,
}

sealed interface CompleteTaskResult {
    data class Completed(
        val task: Task,
        val retainedFutureCount: Int = 0,
        val cancelledFutureCount: Int = 0,
    ) : CompleteTaskResult

    data class NeedsFutureSessionDecision(
        val task: Task,
        val futureSessions: List<WorkSession>,
    ) : CompleteTaskResult
}
