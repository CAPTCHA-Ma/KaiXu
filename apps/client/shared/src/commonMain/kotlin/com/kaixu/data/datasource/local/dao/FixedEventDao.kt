package com.kaixu.data.datasource.local.dao

import androidx.room.*
import com.kaixu.data.datasource.local.entity.*

@Dao
abstract class FixedEventDao {
    @Query("SELECT * FROM fixed_events WHERE deleted_at IS NULL ORDER BY starts_at, id")
    abstract suspend fun list(): List<FixedEventEntity>

    @Query("SELECT * FROM fixed_events WHERE id = :id AND deleted_at IS NULL")
    abstract suspend fun findById(id: String): FixedEventEntity?

    /** 半开区间查询：相邻首尾不算重叠。所有者由调用方提供，不代替身份鉴权。 */
    @Query("SELECT * FROM fixed_events WHERE created_by = :ownerId AND deleted_at IS NULL AND starts_at < :end AND ends_at > :start ORDER BY starts_at, id")
    protected abstract suspend fun queryRange(ownerId: String, start: Long, end: Long): List<FixedEventEntity>

    open suspend fun listInRange(ownerId: String, start: Long, end: Long): List<FixedEventEntity> {
        require(end > start)
        return queryRange(ownerId, start, end)
    }

    @Query("SELECT * FROM fixed_events WHERE workspace_id = :workspaceId AND deleted_at IS NULL ORDER BY starts_at, id")
    abstract suspend fun listByWorkspace(workspaceId: String): List<FixedEventEntity>

    @Insert
    abstract suspend fun insert(entity: FixedEventEntity)

    @Update
    protected abstract suspend fun updateRow(entity: FixedEventEntity): Int

    @Transaction
    open suspend fun update(entity: FixedEventEntity): Int {
        val old = findById(entity.id) ?: return 0
        require(entity.deletedAt == null) { "请使用 deleteById 删除记录" }

        return updateRow(entity.copy(createdAt = old.createdAt, updatedAt = currentTimeMillis(), localRevision = old.localRevision + 1))
    }

    @Query("UPDATE fixed_events SET deleted_at = :at, updated_at = :at, local_revision = local_revision + 1 WHERE id = :id AND deleted_at IS NULL")
    protected abstract suspend fun markDeleted(id: String, at: Long): Int

    open suspend fun deleteById(id: String): Int = markDeleted(id, currentTimeMillis())
}
