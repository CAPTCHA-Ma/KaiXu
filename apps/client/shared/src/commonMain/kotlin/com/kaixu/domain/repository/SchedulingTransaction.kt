package com.kaixu.domain.repository

/** 由本地组合根提供。跨仓储的检查、片段提交和状态变更共享同一个数据库写事务。 */
interface SchedulingTransaction {
    suspend fun <T> run(block: suspend () -> T): T
}
