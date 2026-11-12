package com.kaixu.data.datasource.local.entity

import androidx.room.*
import com.kaixu.domain.model.scheduling.FocusMode

/** 已确认片段只追加，不修改或删除；重试由会话内的 client_segment_id 去重。 */
@Entity(
    tableName = "focus_segments",
    foreignKeys = [ForeignKey(
        entity = FocusSessionEntity::class, parentColumns = ["id"], childColumns = ["session_id"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index(value = ["session_id", "client_segment_id"], unique = true), Index(value = ["session_id", "starts_at"])],
)
data class FocusSegmentEntity(
    @PrimaryKey val id: String = newLocalId(),
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "client_segment_id") val clientSegmentId: String,
    @ColumnInfo(name = "starts_at") val startsAt: Long,
    @ColumnInfo(name = "ends_at") val endsAt: Long,
    val mode: FocusMode,
) {
    init {
        require(id.isNotBlank() && sessionId.isNotBlank() && clientSegmentId.isNotBlank())
        require(endsAt > startsAt) { "有效片段结束必须晚于开始" }
    }
}
