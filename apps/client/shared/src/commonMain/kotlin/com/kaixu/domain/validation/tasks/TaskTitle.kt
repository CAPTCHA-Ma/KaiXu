package com.kaixu.domain.validation.tasks

/** 客户端可复用的输入规则。正式服务端也必须校验，不能只信任客户端。 */
object TaskTitle {
    const val MAX_LENGTH = 120 // 本示例约定，后续纳入接口契约。

    fun errorOrNull(raw: String): String? = when {
        raw.isBlank() -> "请输入任务标题"
        raw.trim().length > MAX_LENGTH -> "标题最多 ${MAX_LENGTH} 个字符"
        else -> null
    }
}

