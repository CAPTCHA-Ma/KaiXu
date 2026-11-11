package com.kaixu.data.datasource.local.dao

import androidx.room.*
import com.kaixu.data.datasource.local.entity.*
import com.kaixu.domain.model.scheduling.FocusSessionStatus

@Dao
abstract class FocusSessionDao {
    @Query("SELECT * FROM focus_sessions WHERE id = :id")
    abstract suspend fun findById(id: String): FocusSessionEntity?

    @Query("SELECT * FROM focus_sessions WHERE owner_user_id = :ownerId AND work_session_id = :workSessionId ORDER BY started_at, id")
    abstract suspend fun listByWorkSession(ownerId: String, workSessionId: String): List<FocusSessionEntity>

    @Query("SELECT * FROM work_sessions WHERE id = :id AND deleted_at IS NULL")
    protected abstract suspend fun findPlan(id: String): WorkSessionEntity?

    @Query("SELECT COUNT(*) FROM focus_sessions WHERE owner_user_id = :ownerId AND id != :exceptId AND status IN ('ACTIVE','PAUSED')")
    protected abstract suspend fun activeCount(ownerId: String, exceptId: String): Int

    @Query("SELECT MAX(ends_at) FROM focus_segments WHERE session_id = :id")
    protected abstract suspend fun latestSegmentEnd(id: String): Long?

    @Insert
    protected abstract suspend fun insertRow(value: FocusSessionEntity)

    @Update
    protected abstract suspend fun updateRow(value: FocusSessionEntity): Int

    private suspend fun validatePlan(value: FocusSessionEntity) {
        val plan = requireNotNull(findPlan(value.workSessionId)) { "计划时段不存在或已删除" }
        require(plan.ownerUserId == value.ownerUserId) { "计时会话必须属于计划时段所有者" }
        require(plan.taskId != null && plan.status == WorkSessionStatus.PLANNED) { "只能为关联任务的计划中时段开启计时" }
    }

    private suspend fun validateSingleActive(value: FocusSessionEntity) {
        if (value.status == FocusSessionStatus.ACTIVE || value.status == FocusSessionStatus.PAUSED) {
            require(activeCount(value.ownerUserId, value.id) == 0) { "本人已有运行或暂停的计时会话" }
        }
    }

    @Transaction
    open suspend fun insert(value: FocusSessionEntity) {
        validatePlan(value)
        validateSingleActive(value)
        insertRow(value)
    }

    @Transaction
    open suspend fun update(value: FocusSessionEntity): Int {
        val old = findById(value.id) ?: return 0
        require(value.ownerUserId == old.ownerUserId && value.workSessionId == old.workSessionId && value.startedAt == old.startedAt) {
            "不能转移计时会话或修改其开始时间"
        }
        if (old.status == FocusSessionStatus.ENDED || old.status == FocusSessionStatus.INTERRUPTED) {
            require(value.status == old.status && value.endedAt == old.endedAt) { "已结束的计时会话不能重开或改写结束时间" }
        }
        if (value.status == FocusSessionStatus.ACTIVE || value.status == FocusSessionStatus.PAUSED) validatePlan(value)
        validateSingleActive(value)
        val latestEnd = latestSegmentEnd(value.id)
        require(value.endedAt == null || latestEnd == null || value.endedAt >= latestEnd) { "会话结束不能早于已确认片段" }
        return updateRow(value.copy(createdAt = old.createdAt, updatedAt = currentTimeMillis()))
    }
}
