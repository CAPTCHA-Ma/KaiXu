package com.kaixu.domain.model.workspaces

/** 当前独立本地空间中的工作区。时间为 UTC 毫秒。 */
data class Workspace(
    val id: String,
    val ownerUserId: String,
    val kind: WorkspaceKind,
    val name: String,
    val description: String?,
    val linkedCourseId: String?,
    val status: WorkspaceStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val archivedAt: Long?,
)

enum class WorkspaceKind { COURSE, PROJECT }
enum class WorkspaceStatus { ACTIVE, ARCHIVED }
