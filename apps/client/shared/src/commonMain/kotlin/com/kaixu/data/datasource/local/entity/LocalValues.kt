package com.kaixu.data.datasource.local.entity

import kotlin.time.Clock
import kotlin.uuid.Uuid

/** 独立本地模式的临时身份；账号/local_meta 接入后由空间身份替代，不能用于授权。 */
const val LOCAL_ACTOR_ID: String = "00000000-0000-0000-0000-000000000001"

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
fun newLocalId(): String = Uuid.random().toString()

@OptIn(kotlin.time.ExperimentalTime::class)
fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

enum class TaskStatus { TODO, IN_PROGRESS, COMPLETED }
enum class TaskPriority { LOW, NORMAL, HIGH }
enum class WorkSessionStatus { PLANNED, COMPLETED, CANCELLED }
// 当前仅实现 LOCAL_ONLY；其余值为后续同步字段预留，不代表已实现同步。
enum class SyncState { LOCAL_ONLY, PENDING, SYNCED, CONFLICT, FAILED }

internal fun isValidDate(value: String): Boolean {
    if (!value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) return false
    val year = value.substring(0, 4).toInt()
    val month = value.substring(5, 7).toInt()
    val day = value.substring(8, 10).toInt()
    if (year < 1 || month !in 1..12) return false
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val days = when (month) {
        2 -> if (leap) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
    return day in 1..days
}
