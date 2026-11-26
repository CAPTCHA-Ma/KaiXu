package com.kaixu.domain.model.scheduling

import com.kaixu.core.platform.LocalZone
import com.kaixu.domain.validation.scheduling.FixedEventForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixedEventDomainAndCalendarLayoutTest {
    private val shanghai = LocalZone.of("Asia/Shanghai")
    private val utc = LocalZone.of("UTC")

    @Test
    fun fixedEventFormValidatesCrossTimezoneRepeatUntilBlankWorkspaceAndNormalizesDraft() {
        // 2026-11-25 18:00Z：UTC 下仍是 2026-11-25，上海下已是 2026-11-26 02:00
        val instant = utc.startOfDayMillis("2026-11-25") + 18L * 3_600_000L
        val draft = FixedEventDraft(
            title = "  数据库实验  ",
            startsAt = instant,
            endsAt = instant + 3_600_000L,
            description = "   ",
            repeatWeekdays = setOf(4, 2, 4),
            repeatUntil = "2026-11-25",
            reminderOffsetMinutes = 15,
        )

        assertNull(FixedEventForm.errorOrNull(draft, utc))
        assertNotNull(FixedEventForm.errorOrNull(draft, shanghai))

        val normalized = FixedEventForm.normalizedDraft(draft, utc)
        assertEquals("数据库实验", normalized.title)
        assertNull(normalized.description)
        assertEquals(listOf(2, 4), normalized.repeatWeekdays?.toList())

        assertFailsWith<IllegalArgumentException> {
            FixedEventForm.normalizedDraft(draft.copy(workspaceId = "   "), utc)
        }
        assertFailsWith<IllegalArgumentException> {
            FixedEventForm.normalizedDraft(draft.copy(reminderOffsetMinutes = -1), utc)
        }
    }

    @Test
    fun calendarWindowSpecBuildsDayAndMondayAnchoredWeekAcrossDst() {
        val daySpec = CalendarWindowSpec.of(CalendarViewMode.DAY, "2026-11-26", shanghai)
        assertEquals(listOf("2026-11-26"), daySpec.dates)
        assertEquals(shanghai.startOfDayMillis("2026-11-26"), daySpec.windowStart)
        assertEquals(shanghai.startOfDayMillis("2026-11-27"), daySpec.windowEnd)

        val thuSpec = CalendarWindowSpec.of(CalendarViewMode.WEEK, "2026-11-26", shanghai)
        val sunSpec = CalendarWindowSpec.of(CalendarViewMode.WEEK, "2026-11-29", shanghai)
        assertEquals("2026-11-23", thuSpec.startDate)
        assertEquals("2026-11-29", thuSpec.endDateInclusive)
        assertEquals(7, thuSpec.dates.size)
        assertEquals(thuSpec.dates, sunSpec.dates)
        assertEquals(thuSpec.windowStart, sunSpec.windowStart)
        assertEquals(thuSpec.windowEnd, sunSpec.windowEnd)

        val nyZone = LocalZone.of("America/New_York")
        val dstWeek = CalendarWindowSpec.of(CalendarViewMode.WEEK, "2026-03-08", nyZone)
        assertEquals("2026-03-02", dstWeek.startDate)
        assertEquals("2026-03-08", dstWeek.endDateInclusive)
        assertEquals((7L * 24L - 1L) * 3_600_000L, dstWeek.windowEnd - dstWeek.windowStart)
    }

    @Test
    fun calendarDayLayoutSlicesCrossMidnightAndMultiDayEventsWithoutBoundarySpill() {
        val spec = CalendarWindowSpec.of(CalendarViewMode.WEEK, "2026-11-24", shanghai)
        val mon = shanghai.startOfDayMillis("2026-11-23")
        val tue = shanghai.startOfDayMillis("2026-11-24")
        val wed = shanghai.startOfDayMillis("2026-11-25")

        val exactMidnightBoundary = CalendarEventBlock(
            key = "midnight-exact",
            kind = CalendarBlockKind.FIXED_EVENT,
            recordId = "e-exact",
            title = "刚好零点结束",
            startsAt = mon + 22L * 3_600_000L,
            endsAt = tue,
        )
        val threeDaySpan = CalendarEventBlock(
            key = "multi-day",
            kind = CalendarBlockKind.FIXED_EVENT,
            recordId = "e-span",
            title = "跨三日集训",
            startsAt = mon + 10L * 3_600_000L,
            endsAt = wed + 15L * 3_600_000L,
        )

        val byDate = CalendarDayLayout.sliceIntoDays(listOf(exactMidnightBoundary, threeDaySpan), spec, shanghai)

        val monSlices = byDate.getValue("2026-11-23")
        assertEquals(2, monSlices.size)
        val exactSlice = monSlices.single { it.block.recordId == "e-exact" }
        assertEquals(22 * 60, exactSlice.startMinuteOfDay)
        assertEquals(1440, exactSlice.endMinuteOfDay)
        assertFalse(exactSlice.continuesFromPreviousDay)
        assertFalse(exactSlice.continuesToNextDay)

        val monSpan = monSlices.single { it.block.recordId == "e-span" }
        assertFalse(monSpan.continuesFromPreviousDay)
        assertTrue(monSpan.continuesToNextDay)
        assertEquals(600, monSpan.startMinuteOfDay)
        assertEquals(1440, monSpan.endMinuteOfDay)

        // 周二不应混入刚好在零点结束的事件，只包含全天持续切片
        val tueSlices = byDate.getValue("2026-11-24")
        val tueSpan = tueSlices.single()
        assertTrue(tueSpan.continuesFromPreviousDay)
        assertTrue(tueSpan.continuesToNextDay)
        assertEquals(0, tueSpan.startMinuteOfDay)
        assertEquals(1440, tueSpan.endMinuteOfDay)

        val wedSpan = byDate.getValue("2026-11-25").single()
        assertTrue(wedSpan.continuesFromPreviousDay)
        assertFalse(wedSpan.continuesToNextDay)
        assertEquals(0, wedSpan.startMinuteOfDay)
        assertEquals(15 * 60, wedSpan.endMinuteOfDay)
    }

    @Test
    fun calendarDayLayoutPacksAdjacentReusedLanesAndDstDayLengthCorrectly() {
        val date = "2026-11-23"
        val dayStart = shanghai.startOfDayMillis(date)
        fun block(id: String, startHour: Int, startMin: Int, endHour: Int, endMin: Int) = CalendarEventBlock(
            key = id,
            kind = CalendarBlockKind.FIXED_EVENT,
            recordId = id,
            title = id,
            startsAt = dayStart + (startHour * 60L + startMin) * 60_000L,
            endsAt = dayStart + (endHour * 60L + endMin) * 60_000L,
        )

        val spec = CalendarWindowSpec.of(CalendarViewMode.DAY, date, shanghai)
        val blocks = listOf(
            // 第一簇：A [09:00,10:00), B [09:30,11:00), C [10:00,11:30) -> C 复用 A 释放的列 0，共用 columnCount=2
            block("a", 9, 0, 10, 0),
            block("b", 9, 30, 11, 0),
            block("c", 10, 0, 11, 30),
            // 紧接其后的 D [11:30,12:00) 不重叠，独立单列 columnCount=1
            block("d", 11, 30, 12, 0),
            // 下午三并发：[14:00,17:00), [14:30,16:00), [15:00,15:30) -> columnCount=3
            block("e", 14, 0, 17, 0),
            block("f", 14, 30, 16, 0),
            block("g", 15, 0, 15, 30),
        )

        val slices = CalendarDayLayout.sliceIntoDays(blocks, spec, shanghai).getValue(date).associateBy { it.block.recordId }
        assertEquals(0 to 2, slices.getValue("a").let { it.columnIndex to it.columnCount })
        assertEquals(1 to 2, slices.getValue("b").let { it.columnIndex to it.columnCount })
        assertEquals(0 to 2, slices.getValue("c").let { it.columnIndex to it.columnCount })
        assertEquals(0 to 1, slices.getValue("d").let { it.columnIndex to it.columnCount })
        assertEquals(0 to 3, slices.getValue("e").let { it.columnIndex to it.columnCount })
        assertEquals(1 to 3, slices.getValue("f").let { it.columnIndex to it.columnCount })
        assertEquals(2 to 3, slices.getValue("g").let { it.columnIndex to it.columnCount })

        // DST 春季少 1 小时（23h = 1380 分钟）时，全天切片上界钳制到 1380
        val nyZone = LocalZone.of("America/New_York")
        val dstDate = "2026-03-08"
        val dstSpec = CalendarWindowSpec.of(CalendarViewMode.DAY, dstDate, nyZone)
        val spanAllDstDay = CalendarEventBlock(
            key = "dst-span",
            kind = CalendarBlockKind.FIXED_EVENT,
            recordId = "dst-span",
            title = "跨夏令时事件",
            startsAt = nyZone.startOfDayMillis("2026-03-07"),
            endsAt = nyZone.startOfDayMillis("2026-03-09"),
        )
        val dstSlice = CalendarDayLayout.sliceIntoDays(listOf(spanAllDstDay), dstSpec, nyZone).getValue(dstDate).single()
        assertEquals(0, dstSlice.startMinuteOfDay)
        assertEquals(1380, dstSlice.endMinuteOfDay)
    }
}
