package com.kaixu.domain.model.scheduling

/** 一次实际计时会话。暂停与继续仍属同一会话，结束后再次开始创建新会话。 */
data class FocusSession(
    val id: String,
    val workSessionId: String,
    val startedAt: Long,
    val ownerUserId: String = "00000000-0000-0000-0000-000000000001",
    val status: FocusSessionStatus = FocusSessionStatus.ACTIVE,
    val endedAt: Long? = null,
    val feedback: String? = null,
)

enum class FocusSessionStatus { ACTIVE, PAUSED, ENDED, INTERRUPTED }
