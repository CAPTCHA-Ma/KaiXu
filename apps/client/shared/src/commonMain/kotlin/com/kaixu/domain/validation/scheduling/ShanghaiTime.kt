package com.kaixu.domain.validation.scheduling

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * 输入/展示用的本地时间转换。
 * 全项目固定 Asia/Shanghai（UTC+8，无夏令时），时区只在这里编码。
 */
@OptIn(ExperimentalTime::class)
object ShanghaiTime {
    private const val OFFSET_MILLIS = 8L * 60 * 60 * 1000

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
}
