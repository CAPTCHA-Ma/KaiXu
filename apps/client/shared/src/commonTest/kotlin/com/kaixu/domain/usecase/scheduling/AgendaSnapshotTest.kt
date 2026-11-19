package com.kaixu.domain.usecase.scheduling

import com.kaixu.domain.validation.scheduling.ShanghaiTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgendaSnapshotTest {
    private val window = AgendaWindow.around("2026-11-14")
    private val now = requireNotNull(ShanghaiTime.parseOrNull("2026-11-14 12:00"))
    private fun entry(key: String, time: Long, end: Long? = null, completed: Boolean = false) =
        AgendaEntry(key, key, AgendaKind.WORK_SESSION, key, time, end, completed = completed)

    @Test fun threeDayWindowUsesLocalMidnightsAndExclusiveEnd() {
        assertEquals(ShanghaiTime.parseOrNull("2026-11-13 00:00"), window.start)
        assertEquals(ShanghaiTime.parseOrNull("2026-11-16 00:00"), window.endExclusive)
    }

    @Test fun ongoingArrangementWinsOverACloserDeadline() {
        val snapshot = AgendaSnapshot(window, listOf(entry("live", now - 3_600_000, now + 60_000), entry("due", now)))
        assertEquals(0, snapshot.nearestIndex(now))
    }

    @Test fun nearestUsesTheEndOfAnIntervalAndSkipsCompletedItems() {
        val snapshot = AgendaSnapshot(window, listOf(
            entry("finished", now - 5_000, now + 5_000, completed = true),
            entry("recent", now - 3_600_000, now - 10_000),
            entry("next", now + 60_000),
        ))
        assertEquals(1, snapshot.nearestIndex(now))
        assertNull(AgendaSnapshot(window, emptyList()).nearestIndex(now))
    }
}
