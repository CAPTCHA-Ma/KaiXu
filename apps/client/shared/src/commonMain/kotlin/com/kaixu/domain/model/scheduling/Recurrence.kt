package com.kaixu.domain.model.scheduling

import com.kaixu.domain.validation.scheduling.ShanghaiTime

/**
 * 每周重复的查询时展开：数据库只保存规则行与例外行，视图按窗口展开课次。
 * 不物化课次，因此跨年的序列也不会写入大量行。
 */
object Recurrence {
    /** 展开窗口内的所有课次；窗口为半开区间 [windowStart, windowEnd)。 */
    fun expand(
        events: List<FixedEvent>,
        overridesIncludingCancelled: List<FixedEvent>,
        windowStart: Long,
        windowEnd: Long,
    ): List<ScheduleOccurrence> {
        if (windowEnd <= windowStart) return emptyList()
        val windowStartDate = ShanghaiTime.localDateOf(windowStart)
        val windowEndDate = ShanghaiTime.localDateOf(windowEnd - 1)
        val overrides = overridesIncludingCancelled
            .filter { it.seriesId != null && it.originalDate != null }
            .associateBy { it.seriesId to it.originalDate }

        val result = mutableListOf<ScheduleOccurrence>()
        for (event in events) {
            if (event.seriesId != null) continue // 例外行随所属序列展开
            val weekdays = event.repeatWeekdays
            if (weekdays == null) {
                if (event.startsAt < windowEnd && event.endsAt > windowStart) {
                    result += event.toOccurrence(
                        key = event.id, seriesId = null, originalDate = null,
                        startsAt = event.startsAt, endsAt = event.endsAt,
                    )
                }
                continue
            }
            val until = event.repeatUntil ?: continue
            val firstDate = ShanghaiTime.localDateOf(event.startsAt)
            val timeOfDay = ShanghaiTime.millisOfDay(event.startsAt)
            val duration = event.endsAt - event.startsAt
            var date = if (ShanghaiTime.compareDates(firstDate, windowStartDate) < 0) windowStartDate else firstDate
            val lastDate = if (ShanghaiTime.compareDates(until, windowEndDate) > 0) windowEndDate else until
            var guard = 0
            while (ShanghaiTime.compareDates(date, lastDate) <= 0 && guard++ < MAX_EXPANDED_DAYS) {
                if (ShanghaiTime.isoWeekday(date) in weekdays) {
                    val override = overrides[event.id to date]
                    val start = ShanghaiTime.atLocalDate(date, timeOfDay)
                    when {
                        override == null -> result += event.toOccurrence(
                            key = "${event.id}@$date",
                            seriesId = event.id,
                            originalDate = date,
                            startsAt = start,
                            endsAt = start + duration,
                        )
                        override.isCancelled -> Unit
                        else -> result += override.toOccurrence(
                            key = "${event.id}@$date",
                            seriesId = event.id,
                            originalDate = date,
                            startsAt = override.startsAt,
                            endsAt = override.endsAt,
                        )
                    }
                }
                date = ShanghaiTime.plusDays(date, 1)
            }
        }
        return result.sortedWith(compareBy({ it.startsAt }, { it.key }))
    }

    /** 草稿在窗口内的课次起止，用于保存前的冲突检查。 */
    fun proposedRanges(draft: FixedEventDraft): List<Pair<Long, Long>> {
        val weekdays = draft.repeatWeekdays
        if (weekdays == null) return listOf(draft.startsAt to draft.endsAt)
        val until = draft.repeatUntil ?: return listOf(draft.startsAt to draft.endsAt)
        val firstDate = ShanghaiTime.localDateOf(draft.startsAt)
        val timeOfDay = ShanghaiTime.millisOfDay(draft.startsAt)
        val duration = draft.endsAt - draft.startsAt
        val ranges = mutableListOf<Pair<Long, Long>>()
        var date = firstDate
        var guard = 0
        while (ShanghaiTime.compareDates(date, until) <= 0 && guard++ < MAX_EXPANDED_DAYS) {
            if (ShanghaiTime.isoWeekday(date) in weekdays) {
                val start = ShanghaiTime.atLocalDate(date, timeOfDay)
                ranges += start to start + duration
            }
            date = ShanghaiTime.plusDays(date, 1)
        }
        return ranges
    }

    private const val MAX_EXPANDED_DAYS = 3660 // 约十年，避免异常输入导致死循环

    private fun FixedEvent.toOccurrence(
        key: String,
        seriesId: String?,
        originalDate: String?,
        startsAt: Long,
        endsAt: Long,
    ) = ScheduleOccurrence(
        key = key,
        eventId = id,
        title = title,
        startsAt = startsAt,
        endsAt = endsAt,
        description = description,
        workspaceId = workspaceId,
        createdBy = createdBy,
        reminderOffsetMinutes = reminderOffsetMinutes,
        seriesId = seriesId,
        originalDate = originalDate,
        repeatWeekdays = repeatWeekdays,
        repeatUntil = repeatUntil,
    )
}
