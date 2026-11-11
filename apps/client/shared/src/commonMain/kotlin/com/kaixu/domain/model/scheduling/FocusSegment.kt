package com.kaixu.domain.model.scheduling

/** 已确认、闭合的有效计时片段 [startsAt, endsAt)，暂停间隔不创建片段。时间为 UTC 毫秒。 */
data class FocusSegment(
    val id: String,
    val sessionId: String,
    /** 同一会话内的稳定提交标识；相同标识重试不能重复累计。 */
    val clientSegmentId: String,
    val startsAt: Long,
    val endsAt: Long,
    val mode: FocusMode,
)

enum class FocusMode { FOREGROUND, BACKGROUND }

/** 原始有效时长，不按计划窗口截断，不按计时目标或百分比封顶。 */
data class FocusDuration(
    val foregroundMillis: Long = 0,
    val backgroundMillis: Long = 0,
) {
    val totalMillis: Long get() = foregroundMillis + backgroundMillis
}
