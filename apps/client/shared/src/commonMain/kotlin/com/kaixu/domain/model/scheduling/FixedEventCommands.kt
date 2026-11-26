package com.kaixu.domain.model.scheduling

import com.kaixu.core.platform.CalendarDate
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.validation.scheduling.FixedEventForm

/** 定位待查看或修改的固定事件：单次事件/整条序列，或重复序列中的某一次课次。 */
sealed interface FixedEventTarget {
    data class SingleOrSeries(val eventId: String) : FixedEventTarget {
        init {
            FixedEventForm.requireId(eventId)
        }
    }

    data class Occurrence(
        val seriesId: String,
        val originalDate: String,
    ) : FixedEventTarget {
        init {
            require(seriesId.isNotBlank()) { "重复序列 ID 不能为空" }
            require(CalendarDate.isValidDate(originalDate)) { "原始课次日期必须是有效的 YYYY-MM-DD" }
        }
    }
}

/** 固定事件详情与编辑弹窗初始化快照，含工作区只读状态与作用域能力。 */
data class FixedEventDetail(
    val target: FixedEventTarget,
    val record: FixedEvent,
    val series: FixedEvent? = null,
    val occurrenceStart: Long,
    val occurrenceEnd: Long,
    val initialDraft: FixedEventDraft,
    val workspace: Workspace? = null,
    val workspaceArchived: Boolean = false,
    val isSharedReadOnly: Boolean = false,
    val canEdit: Boolean = false,
    val canDelete: Boolean = false,
    val supportsScopeSelection: Boolean = false,
)

fun FixedEvent.toDraft(
    occurrenceStart: Long = startsAt,
    occurrenceEnd: Long = endsAt,
    repeatWeekdays: Set<Int>? = this.repeatWeekdays,
    repeatUntil: String? = this.repeatUntil,
): FixedEventDraft = FixedEventDraft(
    title = title,
    startsAt = occurrenceStart,
    endsAt = occurrenceEnd,
    description = description,
    workspaceId = workspaceId,
    repeatWeekdays = repeatWeekdays,
    repeatUntil = repeatUntil,
    reminderOffsetMinutes = reminderOffsetMinutes,
)
