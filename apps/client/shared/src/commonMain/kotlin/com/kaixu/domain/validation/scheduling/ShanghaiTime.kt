package com.kaixu.domain.validation.scheduling

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * 输入/展示用的本地时间转换。
 * 全项目固定 Asia/Shanghai（UTC+8，无夏令时），时区只在这里编码。
 * commonMain 不能使用 java.time，日期运算使用民用历算法。
 */
@OptIn(ExperimentalTime::class)
object ShanghaiTime {
    const val OFFSET_MILLIS: Long = 8L * 60 * 60 * 1000
    private const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000

    /** "2026-11-07 09:00" → UTC 毫秒；格式或日期非法返回 null。 */
    fun parseOrNull(text: String): Long? {
        val normalized = text.trim().replace(' ', 'T')
        if (normalized.length != 16) return null
        return runCatching {
            Instant.parse("$normalized:00+08:00").toEpochMilliseconds()
        }.getOrNull()
    }

    /** UTC 毫秒 → "2026-11-07 09:00"（上海时间）。 */
    fun format(epochMillis: Long): String =
        Instant.fromEpochMilliseconds(epochMillis + OFFSET_MILLIS)
            .toString()
            .take(16)
            .replace('T', ' ')

    /** UTC 毫秒 → 上海本地日期 "2026-11-07"。 */
    fun localDateOf(epochMillis: Long): String {
        val (year, month, day) = civilFromDays(floorDiv(epochMillis + OFFSET_MILLIS, DAY_MILLIS))
        return buildString {
            append(year.toString().padStart(4, '0')); append('-')
            append(month.toString().padStart(2, '0')); append('-')
            append(day.toString().padStart(2, '0'))
        }
    }

    /** 取 UTC 毫秒对应的上海本地“当日已过毫秒数”，用于保留事件的时刻。 */
    fun millisOfDay(epochMillis: Long): Long =
        floorMod(epochMillis + OFFSET_MILLIS, DAY_MILLIS)

    /** 把本地日期与当日毫秒数还原为 UTC 毫秒。 */
    fun atLocalDate(date: String, millisOfDay: Long): Long {
        val days = requireNotNull(epochDayOrNull(date)) { "日期无效：$date" }
        return days * DAY_MILLIS + millisOfDay - OFFSET_MILLIS
    }

    /** 日期加天数；日期非法返回原值。 */
    fun plusDays(date: String, days: Long): String {
        val epochDay = requireNotNull(epochDayOrNull(date)) { "日期无效：$date" }
        val (year, month, day) = civilFromDays(epochDay + days)
        return buildString {
            append(year.toString().padStart(4, '0')); append('-')
            append(month.toString().padStart(2, '0')); append('-')
            append(day.toString().padStart(2, '0'))
        }
    }

    /** ISO 星期：周一 = 1 … 周日 = 7；日期非法返回 0。 */
    fun isoWeekday(date: String): Int {
        val epochDay = epochDayOrNull(date) ?: return 0
        return floorMod(epochDay + 3, 7).toInt() + 1
    }

    /** a、b 的先后比较；任一日期非法时抛异常。 */
    fun compareDates(a: String, b: String): Int {
        val left = requireNotNull(epochDayOrNull(a)) { "日期无效：$a" }
        val right = requireNotNull(epochDayOrNull(b)) { "日期无效：$b" }
        return left.compareTo(right)
    }

    fun isValidDate(date: String): Boolean = epochDayOrNull(date) != null

    /** 两个日期相差的天数（b - a）。 */
    fun daysBetween(a: String, b: String): Long {
        val left = requireNotNull(epochDayOrNull(a)) { "日期无效：$a" }
        val right = requireNotNull(epochDayOrNull(b)) { "日期无效：$b" }
        return right - left
    }

    private fun epochDayOrNull(date: String): Long? {
        if (!date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) return null
        val year = date.substring(0, 4).toInt()
        val month = date.substring(5, 7).toInt()
        val day = date.substring(8, 10).toInt()
        if (month !in 1..12) return null
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val daysInMonth = when (month) {
            2 -> if (leap) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        if (day !in 1..daysInMonth) return null
        return daysFromCivil(year, month, day)
    }

    // Howard Hinnant 的 civil-from-days / days-from-civil，公历、无时区依赖。
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        var y = year.toLong()
        y -= if (month <= 2) 1 else 0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
        val z = days + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val year = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = mp + if (mp < 10) 3 else -9
        return Triple((year + if (month <= 2) 1 else 0).toInt(), month.toInt(), day.toInt())
    }

    private fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if (a % b != 0L && ((a xor b) < 0)) q - 1 else q
    }

    private fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b
}
