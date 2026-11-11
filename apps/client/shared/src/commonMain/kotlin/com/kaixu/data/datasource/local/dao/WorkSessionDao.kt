package com.kaixu.data.datasource.local.dao

import androidx.room.*
import com.kaixu.data.datasource.local.entity.*

@Dao
abstract class WorkSessionDao {
    @Query("SELECT * FROM work_sessions WHERE deleted_at IS NULL ORDER BY starts_at, id")
    abstract suspend fun list(): List<WorkSessionEntity>

    @Query("SELECT * FROM work_sessions WHERE id = :id AND deleted_at IS NULL")
    abstract suspend fun findById(id: String): WorkSessionEntity?

    /** 半开区间查询：相邻首尾不算重叠。所有者由调用方提供，不代替身份鉴权。 */
    @Query("SELECT * FROM work_sessions WHERE owner_user_id = :ownerId AND deleted_at IS NULL AND starts_at < :end AND ends_at > :start ORDER BY starts_at, id")
    protected abstract suspend fun queryRange(ownerId: String, start: Long, end: Long): List<WorkSessionEntity>

    open suspend fun listInRange(ownerId: String, start: Long, end: Long): List<WorkSessionEntity> {
        require(end > start)
        return queryRange(ownerId, start, end)
    }

    @Query("SELECT * FROM work_sessions WHERE task_id = :taskId AND deleted_at IS NULL ORDER BY starts_at, id")
    abstract suspend fun listByTask(taskId: String): List<WorkSessionEntity>

    @Query("SELECT * FROM work_sessions WHERE owner_user_id = :ownerId AND task_id = :taskId AND deleted_at IS NULL ORDER BY starts_at, id")
    abstract suspend fun listByOwnerAndTask(ownerId: String, taskId: String): List<WorkSessionEntity>

    @Query("SELECT COUNT(*) FROM tasks WHERE id = :taskId AND deleted_at IS NULL")
    protected abstract suspend fun activeTaskCount(taskId: String): Int

    @Query("SELECT COUNT(*) FROM focus_sessions WHERE work_session_id = :id")
    protected abstract suspend fun focusSessionCount(id: String): Int

    @Insert
    protected abstract suspend fun insertRow(entity: WorkSessionEntity)

    @Transaction
    open suspend fun insert(entity: WorkSessionEntity) {
        require(entity.taskId == null || activeTaskCount(entity.taskId) == 1) { "关联任务不存在或已删除" }
        insertRow(entity)
    }

    @Transaction
    open suspend fun insertAll(entities: List<WorkSessionEntity>) {
        entities.forEach { insert(it) }
    }

    @Update
    protected abstract suspend fun updateRow(entity: WorkSessionEntity): Int

    @Query("""UPDATE focus_sessions SET status = 'INTERRUPTED',
        ended_at = MAX(:at, started_at, COALESCE((SELECT MAX(ends_at) FROM focus_segments WHERE session_id = focus_sessions.id), started_at)),
        updated_at = :at WHERE work_session_id = :id AND status IN ('ACTIVE','PAUSED')""")
    protected abstract suspend fun interruptFocusSessions(id: String, at: Long)

    @Transaction
    open suspend fun update(entity: WorkSessionEntity): Int {
        val old = findById(entity.id) ?: return 0
        require(entity.ownerUserId == old.ownerUserId) { "不能转移私人时段所有者" }
        require(entity.taskId == null || entity.taskId == old.taskId || focusSessionCount(entity.id) == 0) {
            "已有计时历史的计划时段不能改为关联其他任务"
        }
        require(entity.deletedAt == null) { "请使用 deleteById 删除记录" }
        require(entity.taskId == null || activeTaskCount(entity.taskId) == 1) { "关联任务不存在或已删除" }
        val at = currentTimeMillis()
        if (entity.status != WorkSessionStatus.PLANNED || entity.taskId == null) interruptFocusSessions(entity.id, at)
        return updateRow(entity.copy(createdAt = old.createdAt, updatedAt = at, localRevision = old.localRevision + 1))
    }

    @Query("UPDATE work_sessions SET deleted_at = :at, updated_at = :at, local_revision = local_revision + 1 WHERE id = :id AND deleted_at IS NULL")
    protected abstract suspend fun markDeleted(id: String, at: Long): Int

    @Transaction
    open suspend fun deleteById(id: String): Int {
        if (findById(id) == null) return 0
        val at = currentTimeMillis()
        interruptFocusSessions(id, at)
        return markDeleted(id, at)
    }
}
