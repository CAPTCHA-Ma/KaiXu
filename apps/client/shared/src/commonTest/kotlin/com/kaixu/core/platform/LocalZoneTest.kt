package com.kaixu.core.platform

import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.tasks.TaskDeadline
import com.kaixu.domain.model.tasks.isOverdueAt
import com.kaixu.domain.model.tasks.sortKeyMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalZoneTest {

    @Test
    fun systemDefaultZoneRoundTripsNaturalDate() {
        val zone = LocalZone.systemDefault()
        assertTrue(zone.id.isNotBlank())
        val start = zone.startOfDayMillis("2026-11-25")
        val nextStart = zone.startOfNextDayMillis("2026-11-25")
        assertEquals("2026-11-25", zone.localDateOf(start))
        assertEquals("2026-11-25", zone.localDateOf(nextStart - 1))
        assertEquals("2026-11-26", zone.localDateOf(nextStart))
    }

    @Test
    fun sameInstantProducesDifferentOverdueResultsAcrossZones() {
        val utcZone = LocalZone.of("UTC")
        val shanghaiZone = LocalZone.of("Asia/Shanghai")
        val nyZone = LocalZone.of("America/New_York")

        // 2026-11-25T20:00:00Z：UTC 为 11-25 20:00，纽约为 11-25 15:00，上海为 11-26 04:00
        val instant = utcZone.startOfDayMillis("2026-11-25") + 20L * 3_600_000L
        val task = Task(id = "report", title = "课程报告", deadlineDate = "2026-11-25")

        assertFalse(task.isOverdueAt(instant, utcZone))
        assertFalse(task.isOverdueAt(instant, nyZone))
        assertTrue(task.isOverdueAt(instant, shanghaiZone))
    }

    @Test
    fun daylightSavingTimeTransitionsComputeExactDayLengths() {
        val nyZone = LocalZone.of("America/New_York")

        val springStart = nyZone.startOfDayMillis("2026-03-08")
        val springNext = nyZone.startOfNextDayMillis("2026-03-08")
        assertEquals(23L * 3_600_000L, springNext - springStart)
        assertEquals(
            nyZone.startOfDayMillis("2026-03-09") - 1,
            TaskDeadline.DateOnly("2026-03-08").sortKeyMillis(nyZone),
        )

        val fallStart = nyZone.startOfDayMillis("2026-11-01")
        val fallNext = nyZone.startOfNextDayMillis("2026-11-01")
        assertEquals(25L * 3_600_000L, fallNext - fallStart)
    }

    @Test
    fun invalidZoneAndDateInputsAreRejected() {
        val utcZone = LocalZone.of("UTC")
        assertFailsWith<IllegalArgumentException> { LocalZone.of("") }
        assertFailsWith<IllegalArgumentException> { LocalZone.of("  ") }
        assertFailsWith<IllegalArgumentException> { LocalZone.of("Invalid/Zone") }
        assertFailsWith<IllegalArgumentException> { utcZone.startOfDayMillis("2026-02-29") }
        assertFailsWith<IllegalArgumentException> { CalendarDate.plusDays("0000-01-01", 1) }
        assertFailsWith<IllegalArgumentException> { CalendarDate.plusDays("0001-01-01", -1) }
        assertFailsWith<IllegalArgumentException> { CalendarDate.plusDays("9999-12-31", 1) }
        assertFailsWith<IllegalArgumentException> { utcZone.localTimeOfDayMillis(0L).let { utcZone.atLocalDateTimeMillis("2026-11-25", -1L) } }
        assertFailsWith<IllegalArgumentException> { utcZone.atLocalDateTimeMillis("2026-11-25", 86_400_000L) }
    }

    @Test
    fun calendarDateComputesWeekdayDaysBetweenAndMondayWeekStart() {
        // 2026-11-23 周一 .. 2026-11-29 周日
        assertEquals(1, CalendarDate.isoWeekday("2026-11-23"))
        assertEquals(4, CalendarDate.isoWeekday("2026-11-26"))
        assertEquals(7, CalendarDate.isoWeekday("2026-11-29"))
        assertEquals("2026-11-23", CalendarDate.weekStartMonday("2026-11-23"))
        assertEquals("2026-11-23", CalendarDate.weekStartMonday("2026-11-26"))
        assertEquals("2026-11-23", CalendarDate.weekStartMonday("2026-11-29"))
        assertEquals(3L, CalendarDate.daysBetween("2026-11-23", "2026-11-26"))
        assertEquals(-3L, CalendarDate.daysBetween("2026-11-26", "2026-11-23"))
    }

    @Test
    fun wallClockTimeOfDayPreservesLocalHourAcrossDstTransitions() {
        val nyZone = LocalZone.of("America/New_York")
        val nineAm = 9L * 3_600_000L

        val beforeSpring = nyZone.atLocalDateTimeMillis("2026-03-01", nineAm)
        val afterSpring = nyZone.atLocalDateTimeMillis("2026-03-08", nineAm)
        val afterFall = nyZone.atLocalDateTimeMillis("2026-11-01", nineAm)

        assertEquals(nineAm, nyZone.localTimeOfDayMillis(beforeSpring))
        assertEquals(nineAm, nyZone.localTimeOfDayMillis(afterSpring))
        assertEquals(nineAm, nyZone.localTimeOfDayMillis(afterFall))
        // 2026-03-08 经过 02:00->03:00 跳变，次周同一本地时刻实际间隔少 1 小时
        assertEquals(7L * 24L * 3_600_000L - 3_600_000L, afterSpring - beforeSpring)
    }
}
