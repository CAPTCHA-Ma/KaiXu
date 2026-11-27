package com.kaixu.domain.usecase.scheduling

import com.kaixu.core.platform.LocalZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AgendaSnapshotTest {
    private val shanghai = LocalZone.of("Asia/Shanghai")
    private val window = AgendaWindow.around("2026-11-14", shanghai)
    private val now = shanghai.atLocalDateTimeMillis("2026-11-14", 12L * 3_600_000L)

    private fun entry(key: String, time: Long, end: Long? = null, completed: Boolean = false) =
        AgendaEntry(key, key, AgendaKind.WORK_SESSION, key, time, end, completed = completed)

    @Test
    fun threeDayWindowUsesLocalMidnightsAndExclusiveEndAcrossTimeZones() {
        assertEquals(shanghai.startOfDayMillis("2026-11-13"), window.start)
        assertEquals(shanghai.startOfDayMillis("2026-11-16"), window.endExclusive)

        val utc = LocalZone.of("UTC")
        val utcWindow = AgendaWindow.around("2026-11-14", utc)
        assertEquals(utc.startOfDayMillis("2026-11-13"), utcWindow.start)
        assertEquals(utc.startOfDayMillis("2026-11-16"), utcWindow.endExclusive)
        assertEquals(8L * 3_600_000L, utcWindow.start - window.start)

        // 纽约 2026-11-01 为夏令时回拨日（当日 25 小时），窗口仍准确对齐三日零点
        val newYork = LocalZone.of("America/New_York")
        val dstWindow = AgendaWindow.around("2026-11-01", newYork)
        assertEquals(newYork.startOfDayMillis("2026-10-31"), dstWindow.start)
        assertEquals(newYork.startOfDayMillis("2026-11-03"), dstWindow.endExclusive)
        assertEquals(73L * 3_600_000L, dstWindow.endExclusive - dstWindow.start)

        assertFailsWith<IllegalArgumentException> { AgendaWindow.around("2026-02-30", shanghai) }
    }

    @Test
    fun ongoingArrangementWinsOverACloserDeadline() {
        val snapshot = AgendaSnapshot(window, listOf(entry("live", now - 3_600_000, now + 60_000), entry("due", now)))
        assertEquals(0, snapshot.nearestIndex(now))
    }

    @Test
    fun nearestUsesTheEndOfAnIntervalAndSkipsCompletedItems() {
        val snapshot = AgendaSnapshot(
            window,
            listOf(
                entry("finished", now - 5_000, now + 5_000, completed = true),
                entry("recent", now - 3_600_000, now - 10_000),
                entry("next", now + 60_000),
            ),
        )
        assertEquals(1, snapshot.nearestIndex(now))
        assertNull(AgendaSnapshot(window, emptyList()).nearestIndex(now))
    }
}
