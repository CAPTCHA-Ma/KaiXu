package com.kaixu.domain.model.tasks

/** 供仓储/业务层使用；不包含 Room 或同步存储字段。时间使用 UTC 毫秒。 */
data class Task(
    val id: String = newTaskId(),
    val title: String,
    val description: String? = null,
    val workspaceId: String? = null,
    val createdBy: String = "00000000-0000-0000-0000-000000000001",
    val assigneeUserId: String? = null,
    val status: TaskStatus = TaskStatus.TODO,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val deadlineDate: String? = null,
    val deadlineAt: Long? = null,
    val estimatedMinutes: Int? = null,
    val blockedNote: String? = null,
    val completedAt: Long? = null,
    val sourceExcerpt: String? = null,
    val sourceUrl: String? = null,
)

enum class TaskStatus { TODO, IN_PROGRESS, COMPLETED }
enum class TaskPriority { LOW, NORMAL, HIGH }

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
private fun newTaskId(): String = kotlin.uuid.Uuid.random().toString()
