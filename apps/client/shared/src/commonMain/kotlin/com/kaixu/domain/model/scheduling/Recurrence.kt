package com.kaixu.domain.model.scheduling

import com.kaixu.core.platform.CalendarDate
import com.kaixu.core.platform.LocalZone

/**
 * 每周重复的查询时展开：数据库只保存规则行与例外行，视图按窗口展开课次。
 * 不物化课次，因此跨年的序列也不会写入大量行。
 */
object Recurrence {
    /** 展开半开区间 [windowStart, windowEnd) 内的所有课次（含从窗口前开始但跨入窗口的课次及窗口内改期例外）。 */
    fun expand(
        events: List<FixedEvent>,
        overridesIncludingCancelled: List<FixedEvent>,
        windowStart: Long,
        windowEnd: Long,
        zone: LocalZone = LocalZone.systemDefault(),
    ): List<ScheduleOccurrence> {
        if (windowEnd <= windowStart) return emptyList()
        val windowStartDate = zone.localDateOf(windowStart)
        val windowEndDate = zone.localDateOf(windowEnd - 1L)
        val overridesByKey = overridesIncludingCancelled
            .filter { it.seriesId != null && it.originalDate != null }
            .associateBy { it.seriesId!! to it.originalDate!! }
        val overridesBySeries = overridesByKey.values.groupBy { it.seriesId!! }

        val result = mutableListOf<ScheduleOccurrence>()
        for (event in events) {
            if (event.seriesId != null) continue // 例外行随所属序列展开
            val weekdays = event.repeatWeekdays
            if (weekdays == null) {
                if (event.startsAt < windowEnd && event.endsAt > windowStart) {
                    result += event.toOccurrence(
                        key = event.id,
                        seriesId = null,
                        originalDate = null,
                        startsAt = event.startsAt,
                        endsAt = event.endsAt,
                    )
                }
                continue
            }
            val until = event.repeatUntil ?: continue
            if (!CalendarDate.isValidDate(until)) continue
            val duration = event.endsAt - event.startsAt
            if (duration <= 0L) continue

            val firstDate = zone.localDateOf(event.startsAt)
            if (CalendarDate.compareDates(until, firstDate) < 0) continue

            val timeOfDay = zone.localTimeOfDayMillis(event.startsAt)
            val lookbackDays = (duration / DAY_MILLIS) + 1L
            val shiftedStartDate = minusDaysClamped(windowStartDate, lookbackDays)
            var date = if (CalendarDate.compareDates(firstDate, shiftedStartDate) > 0) {
                firstDate
            } else {
                shiftedStartDate
            }
            val lastDate = if (CalendarDate.compareDates(until, windowEndDate) < 0) until else windowEndDate

            val seriesOverrides = overridesBySeries[event.id].orEmpty()
            val visitedOverrideDates = hashSetOf<String>()
            var guard = 0
            while (CalendarDate.compareDates(date, lastDate) <= 0 && guard++ < MAX_EXPANDED_DAYS) {
                if (CalendarDate.isoWeekday(date) in weekdays) {
                    val override = overridesByKey[event.id to date]
                    when {
                        override == null -> {
                            val start = zone.atLocalDateTimeMillis(date, timeOfDay)
                            val end = start + duration
                            if (start < windowEnd && end > windowStart) {
                                result += event.toOccurrence(
                                    key = "${event.id}@$date",
                                    seriesId = event.id,
                                    originalDate = date,
                                    startsAt = start,
                                    endsAt = end,
                                )
                            }
                        }
                        override.isCancelled -> visitedOverrideDates += date
                        else -> {
                            visitedOverrideDates += date
                            if (override.startsAt < windowEnd && override.endsAt > windowStart) {
                                result += override.toOccurrence(
                                    key = "${event.id}@$date",
                                    seriesId = event.id,
                                    originalDate = date,
                                    startsAt = override.startsAt,
                                    endsAt = override.endsAt,
                                )
                            }
                        }
                    }
                }
                if (date == lastDate) break
                date = CalendarDate.plusDays(date, 1L)
            }

            // 将原本排在窗口外但被单次改期移入当前窗口的有效例外课次纳入结果；已截断日期之后的不恢复。
            for (override in seriesOverrides) {
                if (override.isCancelled) continue
                val origDate = override.originalDate ?: continue
                if (origDate in visitedOverrideDates) continue
                if (!CalendarDate.isValidDate(origDate)) continue
                if (CalendarDate.compareDates(origDate, firstDate) < 0 ||
                    CalendarDate.compareDates(origDate, until) > 0
                ) {
                    continue
                }
                if (CalendarDate.isoWeekday(origDate) !in weekdays) continue
                if (override.startsAt < windowEnd && override.endsAt > windowStart) {
                    result += override.toOccurrence(
                        key = "${event.id}@$origDate",
                        seriesId = event.id,
                        originalDate = origDate,
                        startsAt = override.startsAt,
                        endsAt = override.endsAt,
                    )
                }
            }
        }
        return result.sortedWith(compareBy({ it.startsAt }, { it.key }))
    }

    /** 草稿在规则范围内的全部课次起止，用于保存前的冲突检查。 */
    fun proposedRanges(
        draft: FixedEventDraft,
        zone: LocalZone = LocalZone.systemDefault(),
    ): List<Pair<Long, Long>> {
        val weekdays = draft.repeatWeekdays
        val until = draft.repeatUntil
        if (weekdays == null || until == null) return listOf(draft.startsAt to draft.endsAt)
        if (!CalendarDate.isValidDate(until)) return emptyList()
        val firstDate = zone.localDateOf(draft.startsAt)
        if (CalendarDate.compareDates(until, firstDate) < 0) return emptyList()
        val duration = draft.endsAt - draft.startsAt
        if (duration <= 0L) return emptyList()

        val timeOfDay = zone.localTimeOfDayMillis(draft.startsAt)
        val ranges = mutableListOf<Pair<Long, Long>>()
        var date = firstDate
        var guard = 0
        while (CalendarDate.compareDates(date, until) <= 0 && guard++ < MAX_EXPANDED_DAYS) {
            if (CalendarDate.isoWeekday(date) in weekdays) {
                val start = zone.atLocalDateTimeMillis(date, timeOfDay)
                ranges += start to (start + duration)
            }
            if (date == until) break
            date = CalendarDate.plusDays(date, 1L)
        }
        return ranges
    }

    private const val DAY_MILLIS = 86_400_000L
    private const val MIN_DATE = "0001-01-01"
    private const val MAX_EXPANDED_DAYS = 3660 // 约十年，避免异常输入导致死循环

    private fun minusDaysClamped(date: String, days: Long): String {
        val available = CalendarDate.daysBetween(MIN_DATE, date)
        if (available <= 0L) return MIN_DATE
        return CalendarDate.plusDays(date, -days.coerceAtMost(available))
    }

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
