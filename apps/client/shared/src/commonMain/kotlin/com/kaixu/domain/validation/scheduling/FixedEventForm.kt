package com.kaixu.domain.validation.scheduling

import com.kaixu.core.platform.CalendarDate
import com.kaixu.core.platform.LocalZone
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.FixedEventDraft

object FixedEventForm {
    const val TITLE_MAX_LENGTH = 120

    fun requireId(id: String) {
        require(id.isNotBlank()) { "固定事件 ID 不能为空" }
    }

    fun errorOrNull(
        fixedEvent: FixedEvent,
        zone: LocalZone = LocalZone.systemDefault(),
    ): String? = errorOrNull(
        draft = FixedEventDraft(
            title = fixedEvent.title,
            startsAt = fixedEvent.startsAt,
            endsAt = fixedEvent.endsAt,
            description = fixedEvent.description,
            workspaceId = fixedEvent.workspaceId,
            repeatWeekdays = fixedEvent.repeatWeekdays,
            repeatUntil = fixedEvent.repeatUntil,
            reminderOffsetMinutes = fixedEvent.reminderOffsetMinutes,
        ),
        zone = zone,
    )

    fun errorOrNull(
        draft: FixedEventDraft,
        zone: LocalZone = LocalZone.systemDefault(),
    ): String? = when {
        draft.title.isBlank() -> "请输入事件标题"
        draft.title.trim().length > TITLE_MAX_LENGTH -> "标题最多 $TITLE_MAX_LENGTH 个字符"
        draft.workspaceId != null && draft.workspaceId.isBlank() -> "工作区 ID 不能为空白"
        draft.endsAt <= draft.startsAt -> "结束时间必须晚于开始时间"
        (draft.repeatWeekdays == null) != (draft.repeatUntil == null) -> "重复规则需要同时填写星期与结束日期"
        draft.repeatWeekdays != null && draft.repeatWeekdays.isEmpty() -> "请至少选择一个重复的星期"
        draft.repeatWeekdays != null && draft.repeatWeekdays.any { it !in 1..7 } -> "重复星期必须在 1 到 7 之间"
        draft.repeatUntil != null && !CalendarDate.isValidDate(draft.repeatUntil) -> "重复结束日期无效，请使用 YYYY-MM-DD"
        draft.repeatUntil != null &&
            CalendarDate.compareDates(draft.repeatUntil, zone.localDateOf(draft.startsAt)) < 0 ->
            "重复结束日期不能早于首次事件日期"
        draft.reminderOffsetMinutes != null && draft.reminderOffsetMinutes < 0 -> "提醒提前量不能为负数"
        else -> null
    }

    fun normalizedDraft(
        draft: FixedEventDraft,
        zone: LocalZone = LocalZone.systemDefault(),
    ): FixedEventDraft {
        val normalized = draft.copy(
            title = draft.title.trim(),
            description = draft.description?.trim()?.ifBlank { null },
            repeatWeekdays = draft.repeatWeekdays?.sorted()?.toSet(),
        )
        errorOrNull(normalized, zone)?.let { throw IllegalArgumentException(it) }
        return normalized
    }
}
