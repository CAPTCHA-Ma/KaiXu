package com.kaixu.domain.model.scheduling

/** 供仓储/业务层使用；不包含 Room 或同步存储字段。时间使用 UTC 毫秒。 */
data class FixedEvent(
    val id: String,
    val title: String,
    val startsAt: Long,
    val endsAt: Long,
    val description: String? = null,
    val workspaceId: String? = null,
    val createdBy: String = "00000000-0000-0000-0000-000000000001",
    val reminderOffsetMinutes: Int? = null,
    val seriesId: String? = null,
    val originalDate: String? = null,
    val isOverride: Boolean = false,
)
