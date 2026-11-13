package com.kaixu.domain.validation.scheduling

import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.FixedEventDraft

object FixedEventForm {
    const val TITLE_MAX_LENGTH = 120

    fun errorOrNull(fixedEvent: FixedEvent): String? = errorOrNull(
        FixedEventDraft(
            title = fixedEvent.title,
            startsAt = fixedEvent.startsAt,
            endsAt = fixedEvent.endsAt,
            description = fixedEvent.description,
            workspaceId = fixedEvent.workspaceId,
            repeatWeekdays = fixedEvent.repeatWeekdays,
            repeatUntil = fixedEvent.repeatUntil,
            reminderOffsetMinutes = fixedEvent.reminderOffsetMinutes,
        ),
    )

    fun errorOrNull(draft: FixedEventDraft): String? = when {
        draft.title.isBlank() -> "请输入事件标题"
        draft.title.trim().length > TITLE_MAX_LENGTH -> "标题最多 $TITLE_MAX_LENGTH 个字符"
        draft.endsAt <= draft.startsAt -> "结束时间必须晚于开始时间"
        (draft.repeatWeekdays == null) != (draft.repeatUntil == null) -> "重复规则需要同时填写星期与结束日期"
        draft.repeatWeekdays != null && draft.repeatWeekdays.isEmpty() -> "请至少选择一个重复的星期"
        draft.repeatWeekdays != null && draft.repeatWeekdays.any { it !in 1..7 } -> "重复星期必须在 1 到 7 之间"
        draft.repeatUntil != null && !ShanghaiTime.isValidDate(draft.repeatUntil) -> "重复结束日期无效，请使用 YYYY-MM-DD"
        draft.repeatUntil != null &&
            ShanghaiTime.compareDates(draft.repeatUntil, ShanghaiTime.localDateOf(draft.startsAt)) < 0 ->
            "重复结束日期不能早于首次事件日期"
        draft.reminderOffsetMinutes != null && draft.reminderOffsetMinutes < 0 -> "提醒提前量不能为负数"
        else -> null
    }
}
