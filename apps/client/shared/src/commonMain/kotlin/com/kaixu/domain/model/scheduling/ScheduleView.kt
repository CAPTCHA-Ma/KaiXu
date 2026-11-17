package com.kaixu.domain.model.scheduling

/** 日程条目的来源类型；截止要求不进入执行时间轴。 */
enum class ScheduleEntryType { FIXED_EVENT, SHARED_EVENT, WORK_SESSION }

/** 按日期展示的一条执行安排。时间使用 UTC 毫秒，半开区间。 */
data class ScheduleEntry(
    val key: String,
    val type: ScheduleEntryType,
    val recordId: String,
    val title: String,
    val startsAt: Long,
    val endsAt: Long,
    val workspaceId: String? = null,
    val workspaceName: String? = null,
    val workspaceArchived: Boolean = false,
    val isRecurring: Boolean = false,
    val seriesId: String? = null,
    val originalDate: String? = null,
    val taskId: String? = null,
)

/** 截止要求：与执行时间分开，仅日期或精确时刻二选一。 */
data class ScheduleDeadline(
    val key: String,
    val taskId: String,
    val title: String,
    val date: String? = null,
    val at: Long? = null,
    val workspaceId: String? = null,
    val workspaceName: String? = null,
    val workspaceArchived: Boolean = false,
)

/** 一个查询窗口内的日程：执行时间与截止要求分开返回。 */
data class ScheduleView(
    val entries: List<ScheduleEntry>,
    val deadlines: List<ScheduleDeadline>,
)
