package com.kaixu.domain.model.scheduling

import com.kaixu.core.platform.LocalZone

/** 纯领域日历布局计算：按自然日切分跨日事件，并为同日重叠切片分配并排列序号与总列数。 */
object CalendarDayLayout {
    private const val MINUTE_MILLIS = 60_000L

    fun sliceIntoDays(
        blocks: List<CalendarEventBlock>,
        spec: CalendarWindowSpec,
        zone: LocalZone = LocalZone.systemDefault(),
    ): Map<String, List<CalendarDaySlice>> {
        val byDate = linkedMapOf<String, List<CalendarDaySlice>>()
        for (date in spec.dates) {
            val dayStart = zone.startOfDayMillis(date)
            val dayEnd = zone.startOfNextDayMillis(date)
            val dayTotalMinutes = ((dayEnd - dayStart) / MINUTE_MILLIS).toInt().coerceAtLeast(1)
            val rawSlices = blocks.mapNotNull { block ->
                if (block.endsAt <= block.startsAt || block.startsAt >= dayEnd || block.endsAt <= dayStart) {
                    return@mapNotNull null
                }
                val sliceStart = maxOf(block.startsAt, dayStart)
                val sliceEnd = minOf(block.endsAt, dayEnd)
                val rawStartMinute = ((sliceStart - dayStart) / MINUTE_MILLIS).toInt()
                val rawEndMinute = (((sliceEnd - dayStart) + MINUTE_MILLIS - 1L) / MINUTE_MILLIS)
                    .toInt()
                    .coerceIn(1, dayTotalMinutes)
                val startMinute = rawStartMinute.coerceIn(0, rawEndMinute - 1)
                CalendarDaySlice(
                    sliceKey = "${block.key}#$date",
                    date = date,
                    block = block,
                    sliceStartMillis = sliceStart,
                    sliceEndMillis = sliceEnd,
                    startMinuteOfDay = startMinute,
                    endMinuteOfDay = rawEndMinute,
                    continuesFromPreviousDay = block.startsAt < dayStart,
                    continuesToNextDay = block.endsAt > dayEnd,
                )
            }
            byDate[date] = packOverlappingSlices(rawSlices)
        }
        return byDate
    }

    /** 将连通的时间重叠簇分配到最少列（贪心左对齐），同一连通簇共享一致的 columnCount。 */
    fun packOverlappingSlices(daySlices: List<CalendarDaySlice>): List<CalendarDaySlice> {
        if (daySlices.isEmpty()) return emptyList()
        val sorted = daySlices.sortedWith(
            compareBy<CalendarDaySlice> { it.sliceStartMillis }
                .thenByDescending { it.sliceEndMillis }
                .thenBy { it.sliceKey },
        )

        val packed = mutableListOf<CalendarDaySlice>()
        val cluster = mutableListOf<CalendarDaySlice>()
        var clusterMaxEnd = Long.MIN_VALUE

        for (slice in sorted) {
            if (cluster.isEmpty()) {
                cluster += slice
                clusterMaxEnd = slice.sliceEndMillis
            } else if (slice.sliceStartMillis < clusterMaxEnd) {
                cluster += slice
                if (slice.sliceEndMillis > clusterMaxEnd) {
                    clusterMaxEnd = slice.sliceEndMillis
                }
            } else {
                packed += packCluster(cluster)
                cluster.clear()
                cluster += slice
                clusterMaxEnd = slice.sliceEndMillis
            }
        }
        if (cluster.isNotEmpty()) {
            packed += packCluster(cluster)
        }

        return packed.sortedWith(
            compareBy<CalendarDaySlice> { it.sliceStartMillis }
                .thenBy { it.columnIndex }
                .thenBy { it.sliceKey },
        )
    }

    private fun packCluster(cluster: List<CalendarDaySlice>): List<CalendarDaySlice> {
        val columnEndMillis = mutableListOf<Long>()
        val placements = ArrayList<Pair<CalendarDaySlice, Int>>(cluster.size)
        for (slice in cluster) {
            val reusableColumn = columnEndMillis.indexOfFirst { it <= slice.sliceStartMillis }
            val assignedColumn = if (reusableColumn >= 0) {
                columnEndMillis[reusableColumn] = slice.sliceEndMillis
                reusableColumn
            } else {
                columnEndMillis += slice.sliceEndMillis
                columnEndMillis.lastIndex
            }
            placements += slice to assignedColumn
        }
        val columnCount = columnEndMillis.size
        return placements.map { (slice, columnIndex) ->
            slice.copy(columnIndex = columnIndex, columnCount = columnCount)
        }
    }
}
