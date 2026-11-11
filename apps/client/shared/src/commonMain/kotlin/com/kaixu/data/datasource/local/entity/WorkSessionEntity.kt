package com.kaixu.data.datasource.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "work_sessions",
    indices = [Index(value = ["task_id"]), Index(value = ["owner_user_id", "starts_at"])],
    foreignKeys = [ForeignKey(
        entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["task_id"],
        onDelete = ForeignKey.RESTRICT,
    )],
)
data class WorkSessionEntity(
    @PrimaryKey val id: String = newLocalId(),
    @ColumnInfo(name = "task_id") val taskId: String?,
    @ColumnInfo(name = "starts_at") val startsAt: Long,
    @ColumnInfo(name = "ends_at") val endsAt: Long,
    @ColumnInfo(name = "owner_user_id") val ownerUserId: String = LOCAL_ACTOR_ID,
    @ColumnInfo(name = "stage_id") val stageId: String? = null,
    val note: String? = null,
    val status: WorkSessionStatus = WorkSessionStatus.PLANNED,
    @ColumnInfo(name = "is_locked") val isLocked: Boolean = false,
    @ColumnInfo(name = "overlap_confirmed") val overlapConfirmed: Boolean = false,
    @ColumnInfo(name = "detached_at") val detachedAt: Long? = null,
    @ColumnInfo(name = "detach_reason") val detachReason: String? = null,
    @ColumnInfo(name = "focus_target_seconds") val focusTargetSeconds: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    @ColumnInfo(name = "updated_by") val updatedBy: String = ownerUserId,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "server_version") val serverVersion: Long? = null,
    @ColumnInfo(name = "local_revision") val localRevision: Long = 0,
    @ColumnInfo(name = "sync_state") val syncState: SyncState = SyncState.LOCAL_ONLY,
) {
    init {
        require(endsAt > startsAt)
        require(if (taskId != null) detachedAt == null && detachReason == null
            else detachedAt != null && !detachReason.isNullOrBlank() && stageId == null)
        require(focusTargetSeconds == null || focusTargetSeconds > 0) { "计时目标必须为正数" }
    }
}
