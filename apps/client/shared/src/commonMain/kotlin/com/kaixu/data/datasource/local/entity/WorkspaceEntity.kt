package com.kaixu.data.datasource.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kaixu.domain.model.workspaces.WorkspaceKind
import com.kaixu.domain.model.workspaces.WorkspaceStatus

@Entity(
    tableName = "workspaces",
    foreignKeys = [
        ForeignKey(
            entity = WorkspaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["linked_course_id"],
        ),
    ],
    indices = [Index(value = ["kind", "name"], unique = true), Index(value = ["linked_course_id"])],
)
data class WorkspaceEntity(
    @PrimaryKey val id: String = newLocalId(),
    @ColumnInfo(name = "owner_user_id") val ownerUserId: String = LOCAL_ACTOR_ID,
    val kind: WorkspaceKind,
    val visibility: String = "PERSONAL",
    val name: String,
    val description: String? = null,
    @ColumnInfo(name = "linked_course_id") val linkedCourseId: String? = null,
    val status: WorkspaceStatus = WorkspaceStatus.ACTIVE,
    @ColumnInfo(name = "archived_at") val archivedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    @ColumnInfo(name = "updated_by") val updatedBy: String = ownerUserId,
    @ColumnInfo(name = "server_version") val serverVersion: Long? = null,
    @ColumnInfo(name = "local_revision") val localRevision: Long = 0,
    @ColumnInfo(name = "sync_state") val syncState: SyncState = SyncState.LOCAL_ONLY,
)
