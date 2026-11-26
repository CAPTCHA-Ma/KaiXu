package com.kaixu.domain.validation.tasks

import com.kaixu.core.platform.CalendarDate
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.tasks.TaskDeadline
import com.kaixu.domain.model.tasks.TaskDraft
import com.kaixu.domain.model.tasks.TaskStatus
import com.kaixu.domain.model.tasks.withDeadline

/** 用例和仓储共同使用，直接调用仓储也不能绕过业务字段校验。 */
object TaskInput {
    fun normalized(task: Task): Task {
        requireId(task.id)
        require(task.createdBy.isNotBlank()) { "创建者不能为空" }
        require(task.workspaceId == null || task.workspaceId.isNotBlank()) { "工作区 ID 不能为空白" }
        require(task.assigneeUserId == null || task.assigneeUserId.isNotBlank()) { "负责人 ID 不能为空白" }
        val error = TaskTitle.errorOrNull(task.title)
        require(error == null) { error ?: "无效标题" }
        require(task.deadlineDate == null || task.deadlineAt == null) { "日期截止与精确截止不能同时填写" }
        require(task.deadlineDate == null || isValidDate(task.deadlineDate)) { "截止日期必须是有效的 YYYY-MM-DD" }
        require(task.estimatedMinutes == null || task.estimatedMinutes >= 0) { "预计时长不能为负数" }
        require((task.status == TaskStatus.COMPLETED) == (task.completedAt != null)) { "仅已完成任务必须填写完成时间" }
        return task.copy(title = task.title.trim())
    }

    fun draftErrorOrNull(draft: TaskDraft): String? {
        TaskTitle.errorOrNull(draft.title)?.let { return it }
        if (draft.workspaceId != null && draft.workspaceId.isBlank()) return "工作区 ID 不能为空白"
        if (draft.estimatedMinutes != null && draft.estimatedMinutes < 0) return "预计时长不能为负数"
        if (draft.deadline is TaskDeadline.DateOnly && !isValidDate(draft.deadline.date)) {
            return "截止日期必须是有效的 YYYY-MM-DD"
        }
        return null
    }

    fun normalizedDraft(draft: TaskDraft): TaskDraft {
        draftErrorOrNull(draft)?.let { throw IllegalArgumentException(it) }
        return draft.copy(
            title = draft.title.trim(),
            description = draft.description?.trim()?.ifEmpty { null },
            blockedNote = draft.blockedNote?.trim()?.ifEmpty { null },
        )
    }

    fun toNewTask(
        draft: TaskDraft,
        createdBy: String,
        id: String? = null,
    ): Task {
        require(createdBy.isNotBlank()) { "创建者不能为空" }
        if (id != null) requireId(id)
        val clean = normalizedDraft(draft)
        val base = if (id != null) {
            Task(
                id = id,
                title = clean.title,
                description = clean.description,
                workspaceId = clean.workspaceId,
                createdBy = createdBy,
                status = TaskStatus.TODO,
                priority = clean.priority,
                estimatedMinutes = clean.estimatedMinutes,
                blockedNote = clean.blockedNote,
            )
        } else {
            Task(
                title = clean.title,
                description = clean.description,
                workspaceId = clean.workspaceId,
                createdBy = createdBy,
                status = TaskStatus.TODO,
                priority = clean.priority,
                estimatedMinutes = clean.estimatedMinutes,
                blockedNote = clean.blockedNote,
            )
        }
        return normalized(base.withDeadline(clean.deadline))
    }

    fun applyDraft(
        existing: Task,
        draft: TaskDraft,
        nextStatus: TaskStatus = existing.status,
    ): Task {
        if (existing.status == TaskStatus.COMPLETED && nextStatus != TaskStatus.COMPLETED) {
            throw IllegalArgumentException("已完成任务请通过恢复未完成流程修改状态")
        }
        if (existing.status != TaskStatus.COMPLETED && nextStatus == TaskStatus.COMPLETED) {
            throw IllegalArgumentException("完成状态请通过任务完成流程处理")
        }
        val clean = normalizedDraft(draft)
        return normalized(
            existing.copy(
                title = clean.title,
                description = clean.description,
                workspaceId = clean.workspaceId,
                priority = clean.priority,
                estimatedMinutes = clean.estimatedMinutes,
                blockedNote = clean.blockedNote,
                status = nextStatus,
            ).withDeadline(clean.deadline),
        )
    }

    fun requireId(id: String) {
        require(id.isNotBlank()) { "ID 不能为空" }
    }

    fun isValidDate(value: String): Boolean = CalendarDate.isValidDate(value)
}
