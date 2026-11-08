package com.kaixu.domain.validation.scheduling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        assertNull(ShanghaiTime.parseOrNull(""))
        assertNull(ShanghaiTime.parseOrNull("2026-11-07"))
        assertNull(ShanghaiTime.parseOrNull("2026/11/07 09:00"))
        assertNull(ShanghaiTime.parseOrNull("2026-11-07 9:00"))
    }

    @Test
    fun rejectsImpossibleDate() {
        assertNull(ShanghaiTime.parseOrNull("2026-02-30 09:00"))
        assertNull(ShanghaiTime.parseOrNull("2026-13-01 09:00"))
    }
}
