package com.kaixu.core.platform

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val MILLIS_PER_DAY = 86_400_000L
private val IsoDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd")

private fun ZoneId.formatLocalDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(this).toLocalDate().format(IsoDateFormatter)

private fun ZoneId.dayStartMillis(date: String): Long {
    require(CalendarDate.isValidDate(date)) { "日期无效：$date" }
    return LocalDate.parse(date, IsoDateFormatter).atStartOfDay(this).toInstant().toEpochMilli()
}

private fun ZoneId.timeOfDayMillis(epochMillis: Long): Long =
    Instant.ofEpochMilli(epochMillis).atZone(this).toLocalTime().toNanoOfDay() / 1_000_000L

private fun ZoneId.dateTimeMillis(date: String, timeOfDayMillis: Long): Long {
    require(CalendarDate.isValidDate(date)) { "日期无效：$date" }
    require(timeOfDayMillis in 0L until MILLIS_PER_DAY) { "当日时刻毫秒超出有效范围：$timeOfDayMillis" }
    val localTime = LocalTime.ofNanoOfDay(timeOfDayMillis * 1_000_000L)
    return LocalDate.parse(date, IsoDateFormatter).atTime(localTime).atZone(this).toInstant().toEpochMilli()
}

private object SystemDefaultJavaZone : LocalZone {
    override val id: String
        get() = ZoneId.systemDefault().id

    override fun localDateOf(epochMillis: Long): String =
        ZoneId.systemDefault().formatLocalDate(epochMillis)

    override fun startOfDayMillis(date: String): Long =
        ZoneId.systemDefault().dayStartMillis(date)

    override fun localTimeOfDayMillis(epochMillis: Long): Long =
        ZoneId.systemDefault().timeOfDayMillis(epochMillis)

    override fun atLocalDateTimeMillis(date: String, timeOfDayMillis: Long): Long =
        ZoneId.systemDefault().dateTimeMillis(date, timeOfDayMillis)

    override fun toString(): String = "LocalZone.systemDefault($id)"
}

private data class FixedJavaZone(private val zone: ZoneId) : LocalZone {
    override val id: String get() = zone.id

    override fun localDateOf(epochMillis: Long): String =
        zone.formatLocalDate(epochMillis)

    override fun startOfDayMillis(date: String): Long =
        zone.dayStartMillis(date)

    override fun localTimeOfDayMillis(epochMillis: Long): Long =
        zone.timeOfDayMillis(epochMillis)

    override fun atLocalDateTimeMillis(date: String, timeOfDayMillis: Long): Long =
        zone.dateTimeMillis(date, timeOfDayMillis)
}

internal actual fun platformSystemDefaultZone(): LocalZone = SystemDefaultJavaZone

internal actual fun platformZoneOf(zoneId: String): LocalZone {
    require(zoneId.isNotBlank()) { "时区标识不能为空" }
    val zone = try {
        ZoneId.of(zoneId.trim())
    } catch (invalid: DateTimeException) {
        throw IllegalArgumentException("无效的时区标识：$zoneId", invalid)
    }
    return FixedJavaZone(zone)
}
