package com.kaixu.data.datasource.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kaixu.domain.model.resources.ResourceKind

@Entity(
    tableName = "resources",
    foreignKeys = [ForeignKey(entity = WorkspaceEntity::class, parentColumns = ["id"], childColumns = ["workspace_id"])],
    indices = [Index(value = ["workspace_id", "created_at"])],
)
data class ResourceEntity(
    @PrimaryKey val id: String = newLocalId(),
    @ColumnInfo(name = "workspace_id") val workspaceId: String,
    @ColumnInfo(name = "created_by") val createdBy: String = LOCAL_ACTOR_ID,
    val name: String,
    val description: String? = null,
    val kind: ResourceKind,
    val url: String? = null,
    @ColumnInfo(name = "text_content") val textContent: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    @ColumnInfo(name = "updated_by") val updatedBy: String = createdBy,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "server_version") val serverVersion: Long? = null,
    @ColumnInfo(name = "local_revision") val localRevision: Long = 0,
    @ColumnInfo(name = "sync_state") val syncState: SyncState = SyncState.LOCAL_ONLY,
)
