package com.kaixu.domain.usecase.scheduling

import com.kaixu.data.datasource.FixedEventDataSource
import com.kaixu.data.datasource.WorkSessionDataSource
import com.kaixu.data.datasource.WorkspaceDataSource
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.model.workspaces.WorkspaceStatus

/** 用户确认的是当次表单与冲突快照；内容变化后必须重新确认。 */
data class FixedEventEditConfirmation(
    val proposed: FixedEvent,
    val events: List<FixedEvent>,
    val sessions: List<WorkSession>,
)

sealed interface FixedEventEditResult {
    data object Saved : FixedEventEditResult
    data class NeedsConfirmation(val confirmation: FixedEventEditConfirmation) : FixedEventEditResult
}

/**
 * 独立本地、单次固定事件的编辑入口。只替换可编辑字段，保留原 ID、归属及元数据。
 * 每次保存（含确认后）在同一写事务重新检查原记录、工作区和本人日程。
 * 需要用户确认时先返回快照并结束事务，不跨越用户交互持有数据库锁。
 */
class EditLocalFixedEventUseCase(
    private val events: FixedEventDataSource,
    private val sessions: WorkSessionDataSource,
    private val workspaces: WorkspaceDataSource,
    private val ownerUserId: String,
    private val writeTransaction: suspend (suspend () -> FixedEventEditResult) -> FixedEventEditResult,
) {
    suspend fun save(
        original: FixedEvent,
        title: String,
        description: String?,
        startsAt: Long,
        endsAt: Long,
        confirmed: FixedEventEditConfirmation? = null,
    ): FixedEventEditResult = writeTransaction {
        saveChecked(original, title, description, startsAt, endsAt, confirmed)
    }

    private suspend fun saveChecked(
        original: FixedEvent,
        title: String,
        description: String?,
        startsAt: Long,
        endsAt: Long,
        confirmed: FixedEventEditConfirmation?,
    ): FixedEventEditResult {
        val name = title.trim()
        require(name.isNotEmpty()) { "请输入事件标题。" }
        require(endsAt > startsAt) { "结束时间必须晚于开始时间。" }
        val current = requireNotNull(events.findEventById(original.id)) { "事件已不可用，请关闭编辑并刷新。" }
        require(current.createdBy == ownerUserId) { "只能编辑本人的本地事件。" }
        require(current.seriesId == null) { "重复事件需要在日程模块中选择修改范围。" }
        require(current == original) { "事件已变更，输入已保留。请关闭编辑，刷新后重新核对。" }
        current.workspaceId?.let { id ->
            val workspace = requireNotNull(workspaces.findWorkspaceById(id)) { "所属工作区已不可用，无法保存。" }
            require(workspace.ownerUserId == ownerUserId && workspace.status == WorkspaceStatus.ACTIVE) {
                "所属工作区已归档或不可编辑，无法保存。"
            }
        }
        val proposed = current.copy(
            title = name,
            description = description?.takeUnless { it.isBlank() },
            startsAt = startsAt,
            endsAt = endsAt,
        )
        if (proposed == current) return FixedEventEditResult.Saved
        val overlappingEvents = events.listEventsInRange(ownerUserId, startsAt, endsAt)
            .filter { it.id != current.id && it.createdBy == ownerUserId && it.startsAt < endsAt && it.endsAt > startsAt }
            .sortedBy { it.id }
        val overlappingSessions = sessions.listWorkSessionsInRange(ownerUserId, startsAt, endsAt)
            .filter {
                it.ownerUserId == ownerUserId && it.status != WorkSessionStatus.CANCELLED &&
                    it.startsAt < endsAt && it.endsAt > startsAt
            }.sortedBy { it.id }
        val confirmation = FixedEventEditConfirmation(proposed, overlappingEvents, overlappingSessions)
        if ((overlappingEvents.isNotEmpty() || overlappingSessions.isNotEmpty()) && confirmed != confirmation) {
            return FixedEventEditResult.NeedsConfirmation(confirmation)
        }
        check(events.updateEvent(proposed)) { "事件已不可用，输入已保留。请关闭编辑并刷新。" }
        return FixedEventEditResult.Saved
    }
}
