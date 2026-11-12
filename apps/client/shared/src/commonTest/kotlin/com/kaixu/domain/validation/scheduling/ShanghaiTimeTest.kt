package com.kaixu.domain.validation.scheduling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShanghaiTimeTest {
    @Test
    fun roundTripsShanghaiText() {
        val text = "2026-11-07 09:00"
        assertEquals(text, ShanghaiTime.format(ShanghaiTime.parseOrNull(text)!!))
    }

    @Test
    fun acceptsDateAndTimeSeparatedByT() {
        assertEquals(
            ShanghaiTime.parseOrNull("2026-11-07 09:00"),
            ShanghaiTime.parseOrNull("2026-11-07T09:00"),
        )
    }

    @Test
    fun rejectsMalformedText() {
        assertEquals(null, ShanghaiTime.parseOrNull(""))
        assertEquals(null, ShanghaiTime.parseOrNull("2026-11-07"))
        assertEquals(null, ShanghaiTime.parseOrNull("2026/11/07 09:00"))
        assertEquals(null, ShanghaiTime.parseOrNull("2026-11-07 9:00"))
    }

    @Test
    fun rejectsImpossibleDate() {
        assertEquals(null, ShanghaiTime.parseOrNull("2026-02-30 09:00"))
        assertEquals(null, ShanghaiTime.parseOrNull("2026-13-01 09:00"))
    }

    @Test
    fun localDateUsesShanghaiOffset() {
        // 2026-11-07T16:30Z 是上海 2026-11-08 00:30。
        val millis = ShanghaiTime.parseOrNull("2026-11-08 00:30")!!
        assertEquals("2026-11-08", ShanghaiTime.localDateOf(millis))
        assertEquals("2026-11-08", ShanghaiTime.localDateOf(millis - 30 * 60 * 1000))
        assertEquals("2026-11-07", ShanghaiTime.localDateOf(millis - 31 * 60 * 1000))
    }

    @Test
    fun dateArithmeticCrossesMonthAndYear() {
        assertEquals("2026-12-01", ShanghaiTime.plusDays("2026-11-30", 1))
        assertEquals("2027-01-01", ShanghaiTime.plusDays("2026-12-31", 1))
        assertEquals("2028-02-29", ShanghaiTime.plusDays("2028-02-28", 1))
        assertEquals("2026-11-04", ShanghaiTime.plusDays("2026-11-05", -1))
        assertEquals(2, ShanghaiTime.daysBetween("2026-11-01", "2026-11-03"))
    }

    @Test
    fun isoWeekdayMatchesCalendar() {
        assertEquals(1, ShanghaiTime.isoWeekday("2026-11-02")) // 周一
        assertEquals(4, ShanghaiTime.isoWeekday("2026-11-05")) // 周四
        assertEquals(7, ShanghaiTime.isoWeekday("2026-11-08")) // 周日
        assertEquals(0, ShanghaiTime.isoWeekday("2026-02-30"))
    }

    @Test
    fun atLocalDateRestoresOriginalInstant() {
        val original = ShanghaiTime.parseOrNull("2026-11-07 09:30")!!
        val restored = ShanghaiTime.atLocalDate("2026-11-07", ShanghaiTime.millisOfDay(original))
        assertEquals(original, restored)
    }

    @Test
    fun validatesDates() {
        assertTrue(ShanghaiTime.isValidDate("2028-02-29"))
        assertFalse(ShanghaiTime.isValidDate("2026-02-29"))
        assertFalse(ShanghaiTime.isValidDate("2026-1-01"))
        assertFailsWith<IllegalArgumentException> { ShanghaiTime.plusDays("2026-02-30", 1) }
    }
}
