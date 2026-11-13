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
    /** 每周重复的 ISO 星期序号（周一=1…周日=7）；null 表示一次性事件。 */
    val repeatWeekdays: Set<Int>? = null,
    /** 每周重复的结束日期（含当天，"YYYY-MM-DD"）；与 repeatWeekdays 同时为空或同时非空。 */
    val repeatUntil: String? = null,
    val seriesId: String? = null,
    val originalDate: String? = null,
    val isOverride: Boolean = false,
    /** 已取消的例外课次：保留占位以隐藏序列中的该次。 */
    val isCancelled: Boolean = false,
) {
    val isRecurring: Boolean get() = repeatWeekdays != null
    val isOccurrenceOverride: Boolean get() = seriesId != null && originalDate != null
}

/** 新建/编辑固定事件的业务输入；时间使用 UTC 毫秒。 */
data class FixedEventDraft(
    val title: String,
    val startsAt: Long,
    val endsAt: Long,
    val description: String? = null,
    val workspaceId: String? = null,
    val repeatWeekdays: Set<Int>? = null,
    val repeatUntil: String? = null,
    val reminderOffsetMinutes: Int? = null,
)

/** 编辑重复事件时的影响范围。 */
enum class EditScope { THIS_OCCURRENCE, THIS_AND_FUTURE }

/** 日/周视图中展开后的一条日程。 */
data class ScheduleOccurrence(
    val key: String,
    val eventId: String,
    val title: String,
    val startsAt: Long,
    val endsAt: Long,
    val description: String? = null,
    val workspaceId: String? = null,
    val createdBy: String,
    val reminderOffsetMinutes: Int? = null,
    val seriesId: String? = null,
    val originalDate: String? = null,
    val repeatWeekdays: Set<Int>? = null,
    val repeatUntil: String? = null,
) {
    val isRecurring: Boolean get() = seriesId != null || repeatWeekdays != null
}
