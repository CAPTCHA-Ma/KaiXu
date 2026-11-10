package com.kaixu.domain.validation.tasks

import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.tasks.TaskStatus

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

    fun requireId(id: String) {
        require(id.isNotBlank()) { "ID 不能为空" }
    }

    fun isValidDate(value: String): Boolean {
        if (!value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) return false
        val year = value.substring(0, 4).toInt()
        val month = value.substring(5, 7).toInt()
        val day = value.substring(8, 10).toInt()
        if (year < 1 || month !in 1..12) return false
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val days = when (month) {
            2 -> if (leap) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        return day in 1..days
    }
}
