package com.kaixu.data.datasource.local

import com.kaixu.data.datasource.WorkspaceDataSource
import com.kaixu.data.datasource.local.dao.WorkspaceDao
import com.kaixu.data.datasource.local.entity.WorkspaceEntity
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceKind
import com.kaixu.domain.model.workspaces.WorkspaceStatus

class RoomWorkspaceDataSource(private val dao: WorkspaceDao) : WorkspaceDataSource {
    override suspend fun listWorkspaces(status: WorkspaceStatus?): List<Workspace> =
        dao.list(status).map { it.toDomain() }

    override suspend fun findWorkspaceById(id: String): Workspace? = dao.findById(id)?.toDomain()

    override suspend fun listProjectsForCourse(courseId: String, status: WorkspaceStatus?): List<Workspace> =
        dao.listProjectsForCourse(courseId, status).map { it.toDomain() }

    override suspend fun createWorkspace(
        kind: WorkspaceKind,
        name: String,
        description: String?,
        linkedCourseId: String?,
    ): Workspace = dao.create(kind, name, description, linkedCourseId).toDomain()

    override suspend fun updateWorkspace(id: String, name: String, description: String?, linkedCourseId: String?): Boolean =
        dao.edit(id, name, description, linkedCourseId)

    override suspend fun archiveWorkspace(id: String): Boolean = dao.archive(id)
    override suspend fun restoreWorkspace(id: String): Boolean = dao.restore(id)
}

private fun WorkspaceEntity.toDomain(): Workspace = Workspace(
    id = id,
    ownerUserId = ownerUserId,
    kind = kind,
    name = name,
    description = description,
    linkedCourseId = linkedCourseId,
    status = status,
    createdAt = createdAt,
    updatedAt = updatedAt,
    archivedAt = archivedAt,
)
