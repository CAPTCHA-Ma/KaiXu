package com.kaixu.domain.repository

import com.kaixu.domain.model.tasks.Task
import kotlinx.coroutines.flow.StateFlow

/** 业务动作只依赖此接口，不需要知道内存、缓存或 HTTP 的细节。 */
interface TaskRepository {
    val tasks: StateFlow<List<Task>>
    suspend fun refresh()
    suspend fun create(title: String)
}

