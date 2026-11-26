package com.kaixu.domain.model.scheduling

import com.kaixu.core.platform.CalendarDate
import com.kaixu.core.platform.LocalZone
import com.kaixu.domain.model.tasks.TaskDeadline
import com.kaixu.domain.model.tasks.TaskPriority
import com.kaixu.domain.model.tasks.TaskStatus

/** 日历平铺视图模式（首版仅开放日视图与周视图）。 */
enum class CalendarViewMode { DAY, WEEK }

/** 日历面板独立持有的工作区筛选条件，与右侧任务列表筛选隔离。 */
sealed interface CalendarWorkspaceFilter {
    data object All : CalendarWorkspaceFilter
    data object Standalone : CalendarWorkspaceFilter
    data class Workspace(val id: String) : CalendarWorkspaceFilter {
        init {
            require(id.isNotBlank()) { "工作区 ID 不能为空白" }
        }
    }
}

fun CalendarWorkspaceFilter.matches(workspaceId: String?): Boolean = when (this) {
    CalendarWorkspaceFilter.All -> true
    CalendarWorkspaceFilter.Standalone -> workspaceId == null
    is CalendarWorkspaceFilter.Workspace -> workspaceId == id
}

/** 日视图/周视图的自然日列表与半开时间窗口 [windowStart, windowEnd)。 */
data class CalendarWindowSpec(
    val mode: CalendarViewMode,
    val anchorDate: String,
    val startDate: String,
    val endDateInclusive: String,
    val windowStart: Long,
    val windowEnd: Long,
    val dates: List<String>,
) {
    companion object {
        fun of(
            mode: CalendarViewMode,
            anchorDate: String,
            zone: LocalZone = LocalZone.systemDefault(),
        ): CalendarWindowSpec {
            require(CalendarDate.isValidDate(anchorDate)) { "锚点日期无效：$anchorDate" }
            val dates = when (mode) {
                CalendarViewMode.DAY -> listOf(anchorDate)
                CalendarViewMode.WEEK -> {
                    val monday = CalendarDate.weekStartMonday(anchorDate)
                    (0L..6L).map { offset -> CalendarDate.plusDays(monday, offset) }
                }
            }
            val startDate = dates.first()
            val endDateInclusive = dates.last()
            return CalendarWindowSpec(
                mode = mode,
                anchorDate = anchorDate,
                startDate = startDate,
                endDateInclusive = endDateInclusive,
                windowStart = zone.startOfDayMillis(startDate),
                windowEnd = zone.startOfNextDayMillis(endDateInclusive),
                dates = dates,
            )
        }
    }
}

/** 日/周日历窗口查询条件。 */
data class CalendarQuery(
    val mode: CalendarViewMode = CalendarViewMode.WEEK,
    val anchorDate: String,
    val workspaceFilter: CalendarWorkspaceFilter = CalendarWorkspaceFilter.All,
)

/** 平铺日历时间轴上的日程块来源分类。 */
enum class CalendarBlockKind { FIXED_EVENT, SHARED_EVENT, WORK_SESSION }

/** 日历时间轴上的一条完整事件/时段（未按自然日切分前）。 */
data class CalendarEventBlock(
    val key: String,
    val kind: CalendarBlockKind,
    val recordId: String,
    val title: String,
    val description: String? = null,
    val startsAt: Long,
    val endsAt: Long,
    val workspaceId: String? = null,
    val workspaceName: String? = null,
    val workspaceArchived: Boolean = false,
    val isRecurring: Boolean = false,
    val seriesId: String? = null,
    val originalDate: String? = null,
    val reminderOffsetMinutes: Int? = null,
    val taskId: String? = null,
    val workSessionStatus: WorkSessionStatus? = null,
    val canEdit: Boolean = false,
    val canDelete: Boolean = false,
)

/** 按单个自然日裁剪并完成重叠分列计算后的展示切片。 */
data class CalendarDaySlice(
    val sliceKey: String,
    val date: String,
    val block: CalendarEventBlock,
    val sliceStartMillis: Long,
    val sliceEndMillis: Long,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val continuesFromPreviousDay: Boolean,
    val continuesToNextDay: Boolean,
    val columnIndex: Int = 0,
    val columnCount: Int = 1,
)

/** 日/周顶部截止条中展示的任务截止要求（与执行时间轴分离）。 */
data class CalendarDeadlineItem(
    val key: String,
    val taskId: String,
    val title: String,
    val date: String,
    val deadline: TaskDeadline,
    val status: TaskStatus,
    val priority: TaskPriority,
    val isOverdue: Boolean = false,
    val workspaceId: String? = null,
    val workspaceName: String? = null,
    val workspaceArchived: Boolean = false,
    val canEdit: Boolean = false,
)

/** 单个自然日列的平铺渲染模型（含当日时间切片与当日截止条任务）。 */
data class CalendarDayColumn(
    val date: String,
    val dayStartMillis: Long,
    val dayEndMillis: Long,
    val slices: List<CalendarDaySlice>,
    val deadlines: List<CalendarDeadlineItem>,
)

/** 面向平铺日/周视图的一体化查询结果快照。 */
data class CalendarWindowSnapshot(
    val spec: CalendarWindowSpec,
    val workspaceFilter: CalendarWorkspaceFilter,
    val blocks: List<CalendarEventBlock>,
    val deadlines: List<CalendarDeadlineItem>,
    val days: List<CalendarDayColumn>,
)
