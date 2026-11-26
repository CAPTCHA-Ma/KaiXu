package com.kaixu.domain.model.scheduling

import com.kaixu.core.platform.LocalZone
import com.kaixu.domain.validation.scheduling.ShanghaiTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecurrenceTest {
    private val shanghai = LocalZone.of("Asia/Shanghai")

    private fun millis(text: String) = ShanghaiTime.parseOrNull(text)!!
    private fun date(text: String) = shanghai.localDateOf(millis(text))

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
            zone = shanghai,
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
            zone = shanghai,
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
            zone = shanghai,
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
            zone = shanghai,
        )
        val occurrence = occurrences.single()
        assertEquals("调课", occurrence.title)
        assertEquals("2026-11-04 14:00", ShanghaiTime.format(occurrence.startsAt))
        assertEquals("2026-11-04 15:30", ShanghaiTime.format(occurrence.endsAt))
    }

    @Test
    fun crossMidnightAndMultiDayRecurringOccurrencesAreExpandedIntoNextDayWindow() {
        val overnight = series(
            id = "night-shift",
            startsAt = "2026-11-02 23:00",
            endsAt = "2026-11-03 01:00",
            weekdays = setOf(1),
            until = "2026-11-16",
        )
        val multiDay = series(
            id = "workshop",
            startsAt = "2026-11-02 10:00",
            endsAt = "2026-11-03 16:00",
            weekdays = setOf(1),
            until = "2026-11-16",
        )
        val tuesdayOccurrences = Recurrence.expand(
            listOf(overnight, multiDay),
            emptyList(),
            millis("2026-11-10 00:00"),
            millis("2026-11-11 00:00"),
            zone = shanghai,
        )
        assertEquals(setOf("night-shift@2026-11-09", "workshop@2026-11-09"), tuesdayOccurrences.map { it.key }.toSet())
        val nightOccurrence = tuesdayOccurrences.first { it.eventId == "night-shift" }
        assertEquals("2026-11-09 23:00", ShanghaiTime.format(nightOccurrence.startsAt))
        assertEquals("2026-11-10 01:00", ShanghaiTime.format(nightOccurrence.endsAt))
    }

    @Test
    fun rescheduledOverrideFollowsTargetWindowAndRespectsSeriesTruncation() {
        val event = series(weekdays = setOf(1, 3), until = "2026-11-11")
        val movedLater = FixedEvent(
            id = "ov-later",
            title = "周一移到周三",
            startsAt = millis("2026-11-04 14:00"),
            endsAt = millis("2026-11-04 15:00"),
            seriesId = "series",
            originalDate = "2026-11-02",
            isOverride = true,
        )
        val movedEarlier = FixedEvent(
            id = "ov-earlier",
            title = "周三移到周二",
            startsAt = millis("2026-11-10 10:00"),
            endsAt = millis("2026-11-10 11:00"),
            seriesId = "series",
            originalDate = "2026-11-11",
            isOverride = true,
        )
        val afterUntilOverride = FixedEvent(
            id = "ov-truncated",
            title = "截断后的例外不应出现",
            startsAt = millis("2026-11-04 18:00"),
            endsAt = millis("2026-11-04 19:00"),
            seriesId = "series",
            originalDate = "2026-11-16",
            isOverride = true,
        )

        // 原周一窗口不再出现被移走的 2026-11-02
        assertTrue(
            Recurrence.expand(
                listOf(event),
                listOf(movedLater, movedEarlier, afterUntilOverride),
                millis("2026-11-02 00:00"),
                millis("2026-11-03 00:00"),
                zone = shanghai,
            ).isEmpty(),
        )

        // 周三窗口包含周三原课次 + 从周一移入的例外课次，不包含已截断的 11-16 例外
        val wedOccurrences = Recurrence.expand(
            listOf(event),
            listOf(movedLater, movedEarlier, afterUntilOverride),
            millis("2026-11-04 00:00"),
            millis("2026-11-05 00:00"),
            zone = shanghai,
        )
        assertEquals(listOf("series@2026-11-04", "series@2026-11-02"), wedOccurrences.map { it.key })

        // 周二窗口包含从下周三提前移入的例外课次
        val tueOccurrences = Recurrence.expand(
            listOf(event),
            listOf(movedLater, movedEarlier, afterUntilOverride),
            millis("2026-11-10 00:00"),
            millis("2026-11-11 00:00"),
            zone = shanghai,
        )
        assertEquals(listOf("series@2026-11-11"), tueOccurrences.map { it.key })
        assertEquals("周三移到周二", tueOccurrences.single().title)
    }

    @Test
    fun dstRecurringEventKeepsWallClockHourInInjectedZone() {
        val nyZone = LocalZone.of("America/New_York")
        val nineAm = 9L * 3_600_000L
        val firstStart = nyZone.atLocalDateTimeMillis("2026-03-01", nineAm)
        val nySeries = FixedEvent(
            id = "ny-sunday",
            title = "周日例会",
            startsAt = firstStart,
            endsAt = firstStart + 3_600_000L,
            repeatWeekdays = setOf(7),
            repeatUntil = "2026-03-15",
        )
        val occurrences = Recurrence.expand(
            events = listOf(nySeries),
            overridesIncludingCancelled = emptyList(),
            windowStart = nyZone.startOfDayMillis("2026-03-01"),
            windowEnd = nyZone.startOfNextDayMillis("2026-03-15"),
            zone = nyZone,
        )
        assertEquals(listOf("2026-03-01", "2026-03-08", "2026-03-15"), occurrences.map { it.originalDate })
        assertTrue(occurrences.all { nyZone.localTimeOfDayMillis(it.startsAt) == nineAm })
    }

    @Test
    fun singleEventOnlyAppearsInsideWindow() {
        val event = FixedEvent(id = "one", title = "会议", startsAt = millis("2026-11-05 09:00"), endsAt = millis("2026-11-05 10:00"))
        assertTrue(
            Recurrence.expand(listOf(event), emptyList(), millis("2026-11-04 00:00"), millis("2026-11-06 00:00"), zone = shanghai).isNotEmpty(),
        )
        assertTrue(
            Recurrence.expand(listOf(event), emptyList(), millis("2026-11-06 00:00"), millis("2026-11-07 00:00"), zone = shanghai).isEmpty(),
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
        val ranges = Recurrence.proposedRanges(draft, zone = shanghai)
        assertEquals(3, ranges.size)
        assertEquals(date("2026-11-02 09:00"), shanghai.localDateOf(ranges.first().first))
        assertEquals(60 * 60 * 1000L, ranges.first().second - ranges.first().first)
    }

    @Test
    fun emptyWindowReturnsNothing() {
        assertTrue(Recurrence.expand(listOf(series()), emptyList(), millis("2026-11-05 00:00"), millis("2026-11-05 00:00"), zone = shanghai).isEmpty())
    }
}
