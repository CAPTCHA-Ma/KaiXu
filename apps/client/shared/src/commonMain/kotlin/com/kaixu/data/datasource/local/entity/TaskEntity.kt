package com.kaixu.data.datasource.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import androidx.room.Index

/** UUID 为跨端身份；日期截止存 YYYY-MM-DD，精确时刻存 UTC 毫秒，两者互斥。 */
@Entity(tableName = "tasks", indices = [Index(value = ["workspace_id", "status"]), Index(value = ["assignee_user_id", "status"])])
data class TaskEntity(
    @PrimaryKey val id: String = newLocalId(),
    val title: String,
    val description: String? = null,
    @ColumnInfo(name = "workspace_id") val workspaceId: String? = null,
    @ColumnInfo(name = "created_by") val createdBy: String = LOCAL_ACTOR_ID,
    @ColumnInfo(name = "assignee_user_id") val assigneeUserId: String? = null,
    val status: TaskStatus = TaskStatus.TODO,
    val priority: TaskPriority = TaskPriority.NORMAL,
    @ColumnInfo(name = "deadline_date") val deadlineDate: String? = null,
    @ColumnInfo(name = "deadline_at") val deadlineAt: Long? = null,
    @ColumnInfo(name = "estimated_minutes") val estimatedMinutes: Int? = null,
    @ColumnInfo(name = "blocked_note") val blockedNote: String? = null,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
    @ColumnInfo(name = "source_excerpt") val sourceExcerpt: String? = null,
    @ColumnInfo(name = "source_url") val sourceUrl: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    @ColumnInfo(name = "updated_by") val updatedBy: String = createdBy,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "server_version") val serverVersion: Long? = null,
    @ColumnInfo(name = "local_revision") val localRevision: Long = 0,
    @ColumnInfo(name = "sync_state") val syncState: SyncState = SyncState.LOCAL_ONLY,
) {
    init {
        require(title.isNotBlank()) { "任务标题不能为空" }
        require(deadlineDate == null || deadlineAt == null) { "日期截止与精确截止不能同时填写" }
        require(deadlineDate == null || isValidDate(deadlineDate)) { "截止日期必须是有效的 YYYY-MM-DD" }
        require(estimatedMinutes == null || estimatedMinutes >= 0)
        require((status == TaskStatus.COMPLETED) == (completedAt != null))
    }
}
