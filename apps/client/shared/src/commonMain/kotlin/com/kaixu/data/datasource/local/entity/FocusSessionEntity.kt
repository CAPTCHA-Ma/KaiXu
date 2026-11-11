package com.kaixu.data.datasource.local.entity

import androidx.room.*
import com.kaixu.domain.model.scheduling.FocusSessionStatus

/** 实际计时与计划分表；不冗余 task_id，通过 work_session 关联任务。 */
@Entity(
    tableName = "focus_sessions",
    foreignKeys = [ForeignKey(
        entity = WorkSessionEntity::class, parentColumns = ["id"], childColumns = ["work_session_id"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index(value = ["work_session_id"]), Index(value = ["owner_user_id", "status"])],
)
data class FocusSessionEntity(
    @PrimaryKey val id: String = newLocalId(),
    @ColumnInfo(name = "work_session_id") val workSessionId: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "owner_user_id") val ownerUserId: String = LOCAL_ACTOR_ID,
    val status: FocusSessionStatus = FocusSessionStatus.ACTIVE,
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
    val feedback: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
) {
    init {
        require(id.isNotBlank() && workSessionId.isNotBlank() && ownerUserId.isNotBlank())
        val terminal = status == FocusSessionStatus.ENDED || status == FocusSessionStatus.INTERRUPTED
        require(terminal == (endedAt != null)) { "结束或中断的会话必须填写结束时间，运行或暂停的会话不能填写" }
        require(endedAt == null || endedAt >= startedAt) { "会话结束不能早于开始" }
    }
}
