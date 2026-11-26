package com.kaixu.core.platform

/**
 * 与时区无关的纯公历日期（YYYY-MM-DD，支持年份 0001..9999）校验、比较与加减运算。
 */
object CalendarDate {
    private val DateRegex = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

    fun isValidDate(date: String): Boolean {
        if (!date.matches(DateRegex)) return false
        val year = date.substring(0, 4).toInt()
        val month = date.substring(5, 7).toInt()
        val day = date.substring(8, 10).toInt()
        if (year !in 1..9999 || month !in 1..12) return false
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val daysInMonth = when (month) {
            2 -> if (leap) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        return day in 1..daysInMonth
    }

    fun compareDates(a: String, b: String): Int {
        require(isValidDate(a)) { "日期无效：$a" }
        require(isValidDate(b)) { "日期无效：$b" }
        return a.compareTo(b)
    }

    fun plusDays(date: String, days: Long): String {
        val epochDay = requireEpochDay(date)
        val (nextYear, nextMonth, nextDay) = civilFromDays(epochDay + days)
        return buildString {
            append(nextYear.toString().padStart(4, '0')); append('-')
            append(nextMonth.toString().padStart(2, '0')); append('-')
            append(nextDay.toString().padStart(2, '0'))
        }
    }

    /** ISO 星期序号：周一 = 1 … 周日 = 7。 */
    fun isoWeekday(date: String): Int =
        floorMod(requireEpochDay(date) + 3L, 7L).toInt() + 1

    /** 两个自然日相差的天数（endDate - startDate）。 */
    fun daysBetween(startDate: String, endDate: String): Long =
        requireEpochDay(endDate) - requireEpochDay(startDate)

    /** 返回所属自然周（周一为每周首日）的周一日期。 */
    fun weekStartMonday(date: String): String =
        plusDays(date, -(isoWeekday(date) - 1).toLong())

    private fun requireEpochDay(date: String): Long {
        require(isValidDate(date)) { "日期无效：$date" }
        val year = date.substring(0, 4).toInt()
        val month = date.substring(5, 7).toInt()
        val day = date.substring(8, 10).toInt()
        return daysFromCivil(year, month, day)
    }

    private fun floorMod(a: Long, b: Long): Long {
        val rem = a % b
        return if (rem < 0L) rem + b else rem
    }

    // Howard Hinnant 的 days-from-civil / civil-from-days，公历无时区依赖。
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
        val resolvedYear = year + if (month <= 2) 1 else 0
        require(resolvedYear in 1..9999) { "日期超出支持范围" }
        return Triple(resolvedYear.toInt(), month.toInt(), day.toInt())
    }
}

/**
 * 跨平台时区换算接口：用于自然日（YYYY-MM-DD）与 UTC 毫秒之间的本地时区转换。
 * 默认实现跟随宿主系统当前时区；业务用例与测试可显式注入指定时区。
 */
interface LocalZone {
    val id: String

    /** 将 UTC 毫秒转换为当前时区下的自然日 "YYYY-MM-DD"。 */
    fun localDateOf(epochMillis: Long): String

    /** 返回自然日 "YYYY-MM-DD" 在当前时区下的当日零点（含夏令时跳变修正）对应的 UTC 毫秒。 */
    fun startOfDayMillis(date: String): Long

    /** 返回自然日 "YYYY-MM-DD" 在当前时区下的次日零点（当日结束开区间上界）对应的 UTC 毫秒。 */
    fun startOfNextDayMillis(date: String): Long =
        startOfDayMillis(CalendarDate.plusDays(date, 1))

    /** 返回 UTC 毫秒在当前时区下自本地 00:00:00.000 起经过的墙上时钟毫秒数（0 until 86_400_000）。 */
    fun localTimeOfDayMillis(epochMillis: Long): Long

    /** 将当前时区的自然日与墙上时钟当日毫秒数合成为 UTC 毫秒（跨夏令时保留本地时刻）。 */
    fun atLocalDateTimeMillis(date: String, timeOfDayMillis: Long): Long

    companion object {
        /** 动态跟随操作系统当前默认时区。 */
        fun systemDefault(): LocalZone = platformSystemDefaultZone()

        /** 按标准时区标识（如 "UTC"、"Asia/Shanghai"、"America/New_York"、"+09:00"）构造时区。 */
        fun of(zoneId: String): LocalZone = platformZoneOf(zoneId)
    }
}

internal expect fun platformSystemDefaultZone(): LocalZone
internal expect fun platformZoneOf(zoneId: String): LocalZone
