package com.kaixu.desktop

import com.kaixu.desktop.presentation.ui.scheduling.formatEventEditTime
import com.kaixu.desktop.presentation.ui.scheduling.parseEventEditTime
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class FixedEventEditTimeTest {
    @Test
    fun unchangedDisplayPreservesSecondsAndMilliseconds() {
        val original = Instant.parse("2026-11-08T03:20:47.123Z").toEpochMilli()
        assertEquals("2026-11-08 11:20", formatEventEditTime(original))
        assertEquals(original, parseEventEditTime("2026-11-08 11:20", original))
        assertEquals(Instant.parse("2026-11-08T03:21:00Z").toEpochMilli(), parseEventEditTime("2026-11-08 11:21", original))
    }

    @Test
    fun invalidDatesAndNoncanonicalTimesAreRejected() {
        val original = Instant.parse("2026-11-08T03:20:47.123Z").toEpochMilli()
        for (input in listOf("2026-02-29 10:00", "2026-11-08 24:00", "2026-13-01 10:00", "2026-11-08 10:60", "2026-1-08 10:00", "0000-11-08 10:00")) {
            try {
                parseEventEditTime(input, original)
                fail("无效时间应被拒绝：$input")
            } catch (_: IllegalArgumentException) {
                // 只允许严格有效的上海当地日期与时刻。
            }
        }
    }
}
