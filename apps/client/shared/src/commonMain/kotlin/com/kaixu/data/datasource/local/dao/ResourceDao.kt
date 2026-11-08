package com.kaixu.data.datasource.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.data.datasource.local.entity.ResourceEntity
import com.kaixu.data.datasource.local.entity.WorkspaceEntity
import com.kaixu.data.datasource.local.entity.currentTimeMillis
import com.kaixu.domain.model.resources.ResourceKind
import com.kaixu.domain.model.resources.isResourceHttpUrl
import com.kaixu.domain.model.workspaces.WorkspaceStatus

@Dao
abstract class ResourceDao {
    @Query("SELECT * FROM resources WHERE workspace_id = :workspaceId AND deleted_at IS NULL ORDER BY created_at DESC, id DESC")
    abstract suspend fun listByWorkspace(workspaceId: String): List<ResourceEntity>

    @Query("SELECT * FROM resources WHERE id = :id AND deleted_at IS NULL")
    abstract suspend fun findById(id: String): ResourceEntity?

    @Query("SELECT * FROM workspaces WHERE id = :id")
    protected abstract suspend fun findWorkspace(id: String): WorkspaceEntity?

    @Insert
    protected abstract suspend fun insertRow(value: ResourceEntity)

    @Update
    protected abstract suspend fun updateRow(value: ResourceEntity): Int

    @Transaction
    open suspend fun create(
        workspaceId: String, name: String, description: String?, kind: ResourceKind, url: String?, textContent: String?,
    ): ResourceEntity {
        requireWritableWorkspace(workspaceId)
        val value = ResourceEntity(workspaceId = workspaceId, name = name.trim(), description = description,
            kind = kind, url = url?.trim(), textContent = textContent)
        validate(value)
        insertRow(value)
        return value
    }

    @Transaction
    open suspend fun edit(
        id: String, name: String, description: String?, kind: ResourceKind,
        url: String?, textContent: String?, expectedRevision: Long,
    ): ResourceEntity {
        val old = checkNotNull(findById(id)) { "资料已不可用，请返回列表刷新。" }
        requireWritableWorkspace(old.workspaceId)
        check(old.localRevision == expectedRevision) { "资料已被修改，请取消编辑并刷新后重试。" }
        val edited = old.copy(name = name.trim(), description = description, kind = kind, url = url?.trim(), textContent = textContent)
        validate(edited)
        if (edited == old) return old
        val saved = edited.copy(updatedAt = currentTimeMillis(), updatedBy = LOCAL_ACTOR_ID, localRevision = old.localRevision + 1)
        check(updateRow(saved) == 1) { "资料已不可用，请返回列表刷新。" }
        return saved
    }

    private suspend fun requireWritableWorkspace(id: String) {
        val workspace = checkNotNull(findWorkspace(id)) { "工作区已不可用，请返回列表刷新。" }
        check(workspace.ownerUserId == LOCAL_ACTOR_ID && workspace.visibility == "PERSONAL") { "当前仅支持独立本地资料。" }
        check(workspace.status == WorkspaceStatus.ACTIVE) { "工作区已归档，资料只读。" }
    }

    private fun validate(value: ResourceEntity) {
        require(value.name.isNotBlank()) { "资料名称不能为空。" }
        when (value.kind) {
            ResourceKind.LINK -> {
                require(value.url != null && isResourceHttpUrl(value.url)) { "请输入有效的 http:// 或 https:// 链接。" }
                require(value.textContent == null) { "链接资料不能同时保存文本正文。" }
            }
            ResourceKind.TEXT -> {
                require(!value.textContent.isNullOrBlank()) { "文本内容不能为空。" }
                require(value.url == null) { "文本资料不能同时保存链接。" }
            }
        }
    }
}
