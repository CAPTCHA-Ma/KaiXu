package com.kaixu.domain.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 本地已确认数据的变更修订号信号。
 * 任务、固定事件与个人时段仓储可共享同一实例：写事务成功提交后递增修订号，
 * 供上层按需重读最新数据，避免各处独立缓存脱节。
 */
interface LocalDataRevision {
    val version: StateFlow<Long>
    fun notifyChanged()
}

class LocalDataRevisionTracker(initialVersion: Long = 0L) : LocalDataRevision {
    private val _version = MutableStateFlow(initialVersion)
    override val version: StateFlow<Long> = _version.asStateFlow()

    override fun notifyChanged() {
        _version.update { it + 1L }
    }
}
