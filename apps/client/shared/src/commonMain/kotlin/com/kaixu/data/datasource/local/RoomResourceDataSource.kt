package com.kaixu.data.datasource.local

import com.kaixu.data.datasource.ResourceDataSource
import com.kaixu.data.datasource.local.dao.ResourceDao
import com.kaixu.data.datasource.local.entity.ResourceEntity
import com.kaixu.domain.model.resources.Resource
import com.kaixu.domain.model.resources.ResourceKind

class RoomResourceDataSource(private val dao: ResourceDao) : ResourceDataSource {
    override suspend fun listResourcesByWorkspace(workspaceId: String): List<Resource> = dao.listByWorkspace(workspaceId).map { it.toDomain() }
    override suspend fun findResourceById(id: String): Resource? = dao.findById(id)?.toDomain()
    override suspend fun createResource(workspaceId: String, name: String, description: String?, kind: ResourceKind, url: String?, textContent: String?): Resource =
        dao.create(workspaceId, name, description, kind, url, textContent).toDomain()
    override suspend fun updateResource(id: String, name: String, description: String?, kind: ResourceKind, url: String?, textContent: String?, expectedRevision: Long): Resource =
        dao.edit(id, name, description, kind, url, textContent, expectedRevision).toDomain()
}

private fun ResourceEntity.toDomain() = Resource(id, workspaceId, createdBy, name, description, kind, url, textContent, createdAt, updatedAt, localRevision)
