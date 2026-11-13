package com.kaixu.data.datasource.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kaixu.domain.validation.scheduling.ShanghaiTime

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
    /** 逗号分隔的 ISO 星期序号，例如 "1,3,5"；null 表示一次性事件。 */
    @ColumnInfo(name = "repeat_weekdays") val repeatWeekdays: String? = null,
    /** 重复结束日期（含当天，"YYYY-MM-DD"）；与 repeatWeekdays 同时为空或同时非空。 */
    @ColumnInfo(name = "repeat_until") val repeatUntil: String? = null,
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
        require((repeatWeekdays == null) == (repeatUntil == null)) { "重复星期与重复结束日期必须同时填写" }
        if (repeatWeekdays != null) {
            val days = repeatWeekdays.split(',').mapNotNull { it.toIntOrNull() }
            require(days.isNotEmpty() && days.all { it in 1..7 }) { "重复星期必须在 1 到 7 之间" }
            require(days == days.distinct().sorted()) { "重复星期必须去重并按升序保存" }
            require(seriesId == null) { "重复规则只能保存在序列行，不能保存在例外行" }
            require(ShanghaiTime.isValidDate(repeatUntil!!)) { "重复结束日期无效" }
            require(ShanghaiTime.compareDates(repeatUntil, ShanghaiTime.localDateOf(startsAt)) >= 0) {
                "重复结束日期不能早于首次事件日期"
            }
        } else {
            require(seriesId == null || originalDate != null)
        }
    }
}
