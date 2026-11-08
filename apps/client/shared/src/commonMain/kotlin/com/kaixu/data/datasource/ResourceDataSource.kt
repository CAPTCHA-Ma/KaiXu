package com.kaixu.data.datasource

import com.kaixu.domain.model.resources.Resource
import com.kaixu.domain.model.resources.ResourceKind

interface ResourceDataSource {
    suspend fun listResourcesByWorkspace(workspaceId: String): List<Resource>
    suspend fun findResourceById(id: String): Resource?
    suspend fun createResource(
        workspaceId: String, name: String, description: String?, kind: ResourceKind,
        url: String? = null, textContent: String? = null,
    ): Resource
    suspend fun updateResource(
        id: String, name: String, description: String?, kind: ResourceKind,
        url: String?, textContent: String?, expectedRevision: Long,
    ): Resource
}
