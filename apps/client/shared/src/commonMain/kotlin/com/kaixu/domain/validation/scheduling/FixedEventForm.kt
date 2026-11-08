package com.kaixu.domain.validation.scheduling

import com.kaixu.domain.model.scheduling.FixedEvent

object FixedEventForm {
    const val TITLE_MAX_LENGTH = 120

    fun errorOrNull(fixedEvent: FixedEvent): String? = when {
        fixedEvent.title.isBlank() -> "请输入事件标题"
        fixedEvent.title.trim().length > TITLE_MAX_LENGTH -> "标题最多 $TITLE_MAX_LENGTH 个字符"
        fixedEvent.endsAt <= fixedEvent.startsAt -> "结束时间必须晚于开始时间"
        else -> null
    }
}
