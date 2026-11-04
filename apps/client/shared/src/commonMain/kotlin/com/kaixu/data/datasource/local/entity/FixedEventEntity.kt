package com.kaixu.data.datasource.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "fixed_events", indices = [Index(value = ["workspace_id", "starts_at"]), Index(value = ["created_by", "starts_at"]), Index(value = ["series_id", "original_date"], unique = true)])
data class FixedEventEntity(
    @PrimaryKey val id: String = newLocalId(),
    val title: String,
    @ColumnInfo(name = "starts_at") val startsAt: Long,
    @ColumnInfo(name = "ends_at") val endsAt: Long,
    val description: String? = null,
    @ColumnInfo(name = "workspace_id") val workspaceId: String? = null,
    @ColumnInfo(name = "created_by") val createdBy: String = LOCAL_ACTOR_ID,
    @ColumnInfo(name = "reminder_offset_minutes") val reminderOffsetMinutes: Int? = null,
    @ColumnInfo(name = "series_id") val seriesId: String? = null,
    @ColumnInfo(name = "original_date") val originalDate: String? = null,
    @ColumnInfo(name = "is_override") val isOverride: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    @ColumnInfo(name = "updated_by") val updatedBy: String = createdBy,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "server_version") val serverVersion: Long? = null,
    @ColumnInfo(name = "local_revision") val localRevision: Long = 0,
    @ColumnInfo(name = "sync_state") val syncState: SyncState = SyncState.LOCAL_ONLY,
) {
    init {
        require(title.isNotBlank())
        require(endsAt > startsAt) { "结束必须晚于开始" }
        require(reminderOffsetMinutes == null || reminderOffsetMinutes >= 0)
        require(originalDate == null || isValidDate(originalDate))
        require((seriesId == null) == (originalDate == null))
        require(!isOverride || seriesId != null)
    }
}
