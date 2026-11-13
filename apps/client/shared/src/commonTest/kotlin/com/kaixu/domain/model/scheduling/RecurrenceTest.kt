package com.kaixu.domain.model.scheduling

import com.kaixu.domain.validation.scheduling.ShanghaiTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecurrenceTest {
    private fun millis(text: String) = ShanghaiTime.parseOrNull(text)!!
    private fun date(text: String) = ShanghaiTime.localDateOf(millis(text))

    private fun series(
        id: String = "series",
        startsAt: String = "2026-11-02 09:00",
        endsAt: String = "2026-11-02 10:00",
        weekdays: Set<Int> = setOf(1, 3),
        until: String = "2026-11-20",
    ) = FixedEvent(
        id = id,
        title = "课程",
        startsAt = millis(startsAt),
        endsAt = millis(endsAt),
        repeatWeekdays = weekdays,
        repeatUntil = until,
    )

    @Test
    fun expandsWeeklyOccurrencesInsideWindow() {
        val event = series()
        val occurrences = Recurrence.expand(
            listOf(event), emptyList(),
            millis("2026-11-01 00:00"), millis("2026-11-15 00:00"),
        )
        // 2026-11-02 是周一，2026-11-04 是周三。
        assertEquals(
            listOf("2026-11-02 09:00", "2026-11-04 09:00", "2026-11-09 09:00", "2026-11-11 09:00"),
            occurrences.map { ShanghaiTime.format(it.startsAt) },
        )
        assertEquals(setOf("series"), occurrences.map { it.eventId }.toSet())
        assertEquals(listOf("2026-11-02", "2026-11-04", "2026-11-09", "2026-11-11"), occurrences.map { it.originalDate })
    }

    @Test
    fun stopsAtRepeatUntilInclusive() {
        val event = series(until = "2026-11-04")
        val occurrences = Recurrence.expand(
            listOf(event), emptyList(),
            millis("2026-11-01 00:00"), millis("2026-12-01 00:00"),
        )
        assertEquals(listOf("2026-11-02 09:00", "2026-11-04 09:00"), occurrences.map { ShanghaiTime.format(it.startsAt) })
    }

    @Test
    fun cancelledOccurrenceDisappearsButOthersRemain() {
        val event = series()
        val cancelled = FixedEvent(
            id = "override", title = "课程", startsAt = millis("2026-11-04 09:00"),
            endsAt = millis("2026-11-04 10:00"), seriesId = "series",
            originalDate = "2026-11-04", isOverride = true, isCancelled = true,
        )
        val occurrences = Recurrence.expand(
            listOf(event), listOf(cancelled),
            millis("2026-11-01 00:00"), millis("2026-11-06 00:00"),
        )
        assertEquals(listOf("2026-11-02 09:00"), occurrences.map { ShanghaiTime.format(it.startsAt) })
    }

    @Test
    fun editedOccurrenceUsesOverrideContent() {
        val event = series()
        val override = FixedEvent(
            id = "override", title = "调课", startsAt = millis("2026-11-04 14:00"),
            endsAt = millis("2026-11-04 15:30"), seriesId = "series",
            originalDate = "2026-11-04", isOverride = true,
        )
        val occurrences = Recurrence.expand(
            listOf(event), listOf(override),
            millis("2026-11-04 00:00"), millis("2026-11-05 00:00"),
        )
        val occurrence = occurrences.single()
        assertEquals("调课", occurrence.title)
        assertEquals("2026-11-04 14:00", ShanghaiTime.format(occurrence.startsAt))
        assertEquals("2026-11-04 15:30", ShanghaiTime.format(occurrence.endsAt))
    }

    @Test
    fun singleEventOnlyAppearsInsideWindow() {
        val event = FixedEvent(id = "one", title = "会议", startsAt = millis("2026-11-05 09:00"), endsAt = millis("2026-11-05 10:00"))
        assertTrue(
            Recurrence.expand(listOf(event), emptyList(), millis("2026-11-04 00:00"), millis("2026-11-06 00:00")).isNotEmpty(),
        )
        assertTrue(
            Recurrence.expand(listOf(event), emptyList(), millis("2026-11-06 00:00"), millis("2026-11-07 00:00")).isEmpty(),
        )
    }

    @Test
    fun proposedRangesFollowWeekdaysAndUntil() {
        val draft = FixedEventDraft(
            title = "课程",
            startsAt = millis("2026-11-02 09:00"),
            endsAt = millis("2026-11-02 10:00"),
            repeatWeekdays = setOf(1),
            repeatUntil = "2026-11-16",
        )
        val ranges = Recurrence.proposedRanges(draft)
        assertEquals(3, ranges.size)
        assertEquals(date("2026-11-02 09:00"), ShanghaiTime.localDateOf(ranges.first().first))
        assertEquals(60 * 60 * 1000L, ranges.first().second - ranges.first().first)
    }

    @Test
    fun emptyWindowReturnsNothing() {
        assertTrue(Recurrence.expand(listOf(series()), emptyList(), millis("2026-11-05 00:00"), millis("2026-11-05 00:00")).isEmpty())
    }
}
