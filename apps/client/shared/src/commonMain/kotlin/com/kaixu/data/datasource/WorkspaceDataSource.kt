package com.kaixu.data.datasource

import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceKind
import com.kaixu.domain.model.workspaces.WorkspaceStatus

/** 独立本地工作区。目录默认展示进行中，传 null 同时包含已归档记录。 */
interface WorkspaceDataSource {
    suspend fun listWorkspaces(status: WorkspaceStatus? = WorkspaceStatus.ACTIVE): List<Workspace>
    suspend fun findWorkspaceById(id: String): Workspace?
    suspend fun listProjectsForCourse(courseId: String, status: WorkspaceStatus? = WorkspaceStatus.ACTIVE): List<Workspace>

    suspend fun createWorkspace(
        kind: WorkspaceKind,
        name: String,
        description: String? = null,
        linkedCourseId: String? = null,
    ): Workspace

    /** 传入期望保留的课程 ID，或传 null 解除关联。工作区不存在或已归档时返回 false。 */
    suspend fun updateWorkspace(id: String, name: String, description: String?, linkedCourseId: String?): Boolean
    suspend fun archiveWorkspace(id: String): Boolean
    suspend fun restoreWorkspace(id: String): Boolean
}
