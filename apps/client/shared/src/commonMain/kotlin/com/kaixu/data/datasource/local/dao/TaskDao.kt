package com.kaixu.data.datasource.local.dao

import androidx.room.*
import com.kaixu.data.datasource.local.entity.*

@Dao
abstract class TaskDao {
    @Query("SELECT * FROM tasks WHERE deleted_at IS NULL ORDER BY created_at DESC, id DESC")
    abstract suspend fun list(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id AND deleted_at IS NULL")
    abstract suspend fun findById(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE workspace_id = :workspaceId AND deleted_at IS NULL ORDER BY created_at DESC, id DESC")
    abstract suspend fun listByWorkspace(workspaceId: String): List<TaskEntity>

    @Insert
    abstract suspend fun insert(task: TaskEntity)

    @Update
    protected abstract suspend fun updateRow(task: TaskEntity): Int

    @Transaction
    open suspend fun update(task: TaskEntity): Int {
        val old = findById(task.id) ?: return 0
        require(task.deletedAt == null) { "请使用 deleteById 删除任务" }
        return updateRow(task.copy(createdAt = old.createdAt, updatedAt = currentTimeMillis(), localRevision = old.localRevision + 1))
    }

    @Query("UPDATE tasks SET title = :title, updated_at = :at, local_revision = local_revision + 1 WHERE id = :id AND deleted_at IS NULL")
    protected abstract suspend fun rename(id: String, title: String, at: Long): Int

    open suspend fun updateTitle(id: String, title: String): Int {
        require(title.isNotBlank())
        return rename(id, title, currentTimeMillis())
    }

    @Query("UPDATE work_sessions SET task_id = NULL, stage_id = NULL, detached_at = :at, detach_reason = 'TASK_DELETED', updated_at = :at, local_revision = local_revision + 1 WHERE task_id = :id")
    protected abstract suspend fun detachSessions(id: String, at: Long)

    @Query("UPDATE tasks SET deleted_at = :at, updated_at = :at, local_revision = local_revision + 1 WHERE id = :id AND deleted_at IS NULL")
    protected abstract suspend fun markDeleted(id: String, at: Long): Int

    /** 仅处理当前三表的本地删除；阶段、资料与同步队列接入时需扩展事务。 */
    @Transaction
    open suspend fun deleteById(id: String): Int {
        if (findById(id) == null) return 0
        val at = currentTimeMillis()
        detachSessions(id, at)
        return markDeleted(id, at)
    }
}
