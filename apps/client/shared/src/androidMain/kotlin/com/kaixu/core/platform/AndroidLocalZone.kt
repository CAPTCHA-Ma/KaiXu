package com.kaixu.core.platform

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val IsoDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd")

private object SystemDefaultAndroidZone : LocalZone {
    override val id: String
        get() = ZoneId.systemDefault().id

    override fun localDateOf(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate().format(IsoDateFormatter)

    override fun startOfDayMillis(date: String): Long {
        require(CalendarDate.isValidDate(date)) { "日期无效：$date" }
        return LocalDate.parse(date, IsoDateFormatter).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    override fun toString(): String = "LocalZone.systemDefault($id)"
}

private data class FixedAndroidZone(private val zone: ZoneId) : LocalZone {
    override val id: String get() = zone.id

    override fun localDateOf(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().format(IsoDateFormatter)

    override fun startOfDayMillis(date: String): Long {
        require(CalendarDate.isValidDate(date)) { "日期无效：$date" }
        return LocalDate.parse(date, IsoDateFormatter).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

internal actual fun platformSystemDefaultZone(): LocalZone = SystemDefaultAndroidZone

internal actual fun platformZoneOf(zoneId: String): LocalZone {
    require(zoneId.isNotBlank()) { "时区标识不能为空" }
    val zone = try {
        ZoneId.of(zoneId.trim())
    } catch (invalid: DateTimeException) {
        throw IllegalArgumentException("无效的时区标识：$zoneId", invalid)
    }
    return FixedAndroidZone(zone)
}
