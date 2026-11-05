package com.kaixu.data.datasource.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.kaixu.data.datasource.local.entity.WorkspaceEntity
import com.kaixu.data.datasource.local.entity.currentTimeMillis
import com.kaixu.domain.model.workspaces.WorkspaceKind
import com.kaixu.domain.model.workspaces.WorkspaceStatus

@Dao
abstract class WorkspaceDao {
    @Query("SELECT * FROM workspaces WHERE :status IS NULL OR status = :status ORDER BY created_at DESC, id DESC")
    abstract suspend fun list(status: WorkspaceStatus?): List<WorkspaceEntity>

    @Query("SELECT * FROM workspaces WHERE id = :id")
    abstract suspend fun findById(id: String): WorkspaceEntity?

    @Query("""
        SELECT * FROM workspaces WHERE kind = 'PROJECT' AND linked_course_id = :courseId
        AND (:status IS NULL OR status = :status) ORDER BY created_at DESC, id DESC
    """)
    abstract suspend fun listProjectsForCourse(courseId: String, status: WorkspaceStatus?): List<WorkspaceEntity>

    @Query("""
        SELECT EXISTS(SELECT 1 FROM workspaces WHERE kind = :kind AND name = :name
        AND (:excludingId IS NULL OR id != :excludingId))
    """)
    protected abstract suspend fun nameInUse(kind: WorkspaceKind, name: String, excludingId: String?): Boolean

    @Insert
    protected abstract suspend fun insertRow(value: WorkspaceEntity)

    @Update
    protected abstract suspend fun updateRow(value: WorkspaceEntity): Int

    @Transaction
    open suspend fun create(
        kind: WorkspaceKind,
        name: String,
        description: String?,
        linkedCourseId: String?,
    ): WorkspaceEntity {
        val trimmedName = validatedName(kind, name, excludingId = null)
        validateNewLink(kind, linkedCourseId)
        val value = WorkspaceEntity(kind = kind, name = trimmedName, description = description, linkedCourseId = linkedCourseId)
        insertRow(value)
        return value
    }

    @Transaction
    open suspend fun edit(id: String, name: String, description: String?, linkedCourseId: String?): Boolean {
        val old = findById(id) ?: return false
        if (old.status != WorkspaceStatus.ACTIVE) return false
        val trimmedName = validatedName(old.kind, name, excludingId = id)
        // 目标课程后来归档时，仍允许保留原关联并编辑项目的其他字段。
        if (linkedCourseId != old.linkedCourseId) validateNewLink(old.kind, linkedCourseId)
        if (trimmedName == old.name && description == old.description && linkedCourseId == old.linkedCourseId) return true
        return updateRow(old.copy(
            name = trimmedName,
            description = description,
            linkedCourseId = linkedCourseId,
            updatedAt = currentTimeMillis(),
            localRevision = old.localRevision + 1,
        )) == 1
    }

    private suspend fun validatedName(kind: WorkspaceKind, name: String, excludingId: String?): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "工作区名称不能为空" }
        // 用 Unicode 码点计数，补充平面的汉字和 emoji 各算一个字符。
        val surrogatePairs = (1 until trimmed.length).count { trimmed[it - 1].isHighSurrogate() && trimmed[it].isLowSurrogate() }
        require(trimmed.length - surrogatePairs <= 100) { "工作区名称最多 100 个字符" }
        require(!nameInUse(kind, trimmed, excludingId)) { "该类型的工作区已使用这个名称" }
        return trimmed
    }

    private suspend fun validateNewLink(kind: WorkspaceKind, courseId: String?) {
        if (courseId == null) return
        require(kind == WorkspaceKind.PROJECT) { "只有项目可以关联课程" }
        val course = findById(courseId)
        require(course != null && course.kind == WorkspaceKind.COURSE && course.status == WorkspaceStatus.ACTIVE) {
            "只能关联本地空间中进行中的课程"
        }
    }

    @Query("""
        UPDATE workspaces SET status = :newStatus, archived_at = :archivedAt, updated_at = :at,
        local_revision = local_revision + 1 WHERE id = :id AND status = :expected
    """)
    protected abstract suspend fun setStatus(
        id: String,
        expected: WorkspaceStatus,
        newStatus: WorkspaceStatus,
        archivedAt: Long?,
        at: Long,
    ): Int

    suspend fun archive(id: String): Boolean {
        val now = currentTimeMillis()
        return setStatus(id, WorkspaceStatus.ACTIVE, WorkspaceStatus.ARCHIVED, archivedAt = now, at = now) == 1
    }

    suspend fun restore(id: String): Boolean =
        setStatus(id, WorkspaceStatus.ARCHIVED, WorkspaceStatus.ACTIVE, archivedAt = null, at = currentTimeMillis()) == 1
}
