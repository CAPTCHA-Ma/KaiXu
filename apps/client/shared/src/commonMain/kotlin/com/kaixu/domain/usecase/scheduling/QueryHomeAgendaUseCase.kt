package com.kaixu.domain.usecase.scheduling

import com.kaixu.core.platform.LocalZone
import com.kaixu.domain.model.scheduling.Recurrence
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.tasks.TaskDeadline
import com.kaixu.domain.model.tasks.TaskStatus
import com.kaixu.domain.model.tasks.deadline
import com.kaixu.domain.model.tasks.sortKeyMillis
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.repository.FixedEventRepository
import com.kaixu.domain.repository.TaskRepository
import com.kaixu.domain.repository.WorkSessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** 首页瀑布流完整聚合快照：含三日日程条目与供快速录入使用的本人进行中工作区列表。 */
data class HomeAgendaFeed(
    val agenda: AgendaSnapshot,
    val activeWorkspaces: List<Workspace> = emptyList(),
)

/**
 * 面向桌面首页三日瀑布流（昨天..明天）的只读领域聚合用例：
 * 统一经由 [TaskRepository]、[FixedEventRepository] 与 [WorkSessionRepository] 读取数据，
 * 复用 [Recurrence.expand]、[LocalZone] 与 [TaskDeadline] 规则，并通过去重后的共享修订信号支持响应式重算。
 */
class QueryHomeAgendaUseCase(
    private val taskRepository: TaskRepository,
    private val fixedEventRepository: FixedEventRepository,
    private val workSessionRepository: WorkSessionRepository,
    private val defaultZone: LocalZone = LocalZone.systemDefault(),
) {
    init {
        val ownerId = taskRepository.ownerUserId
        require(
            fixedEventRepository.ownerUserId == ownerId &&
                workSessionRepository.ownerUserId == ownerId,
        ) { "首页聚合用例关联的仓储必须绑定同一本地所有者" }
    }

    suspend operator fun invoke(
        window: AgendaWindow,
        zone: LocalZone = defaultZone,
    ): AgendaSnapshot = loadFeed(window, zone).agenda

    suspend fun loadFeed(
        today: String,
        zone: LocalZone = defaultZone,
    ): HomeAgendaFeed = loadFeed(AgendaWindow.around(today, zone), zone)

    suspend fun loadFeed(
        window: AgendaWindow,
        zone: LocalZone = defaultZone,
    ): HomeAgendaFeed {
        val ownerId = taskRepository.ownerUserId
        val ownerSpaces = taskRepository.listWorkspaces(status = null)
            .filter { it.ownerUserId == ownerId }
        val spacesById = ownerSpaces.associateBy { it.id }
        val activeWorkspaces = ownerSpaces
            .filter { it.status == WorkspaceStatus.ACTIVE }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.name }))

        fun workspaceLabel(id: String?): String? = spacesById[id]?.let {
            it.name + if (it.status == WorkspaceStatus.ARCHIVED) " · 已归档" else ""
        }

        val ownerTasks = taskRepository.listAll()
            .filter { it.createdBy == ownerId || it.assigneeUserId == ownerId }
        val tasksById = ownerTasks.associateBy { it.id }
        val extraTaskCache = hashMapOf<String, Task?>()

        suspend fun resolveSessionTask(taskId: String?): Task? {
            if (taskId == null) return null
            tasksById[taskId]?.let { return it }
            return if (taskId in extraTaskCache) {
                extraTaskCache[taskId]
            } else {
                workSessionRepository.findTaskById(taskId).also { extraTaskCache[taskId] = it }
            }
        }

        val ownerEvents = fixedEventRepository.listAll()
            .filter { it.createdBy == ownerId }
        val ownerOverrides = fixedEventRepository.listOverridesIncludingDeleted()
            .filter { it.createdBy == ownerId }
        val occurrences = Recurrence.expand(
            events = ownerEvents,
            overridesIncludingCancelled = ownerOverrides,
            windowStart = window.start,
            windowEnd = window.endExclusive,
            zone = zone,
        )

        val ownerSessions = workSessionRepository.listInRange(window.start, window.endExclusive)
            .filter {
                it.ownerUserId == ownerId &&
                    it.status != WorkSessionStatus.CANCELLED &&
                    it.startsAt < window.endExclusive &&
                    it.endsAt > window.start
            }

        val entries = buildList {
            for (occurrence in occurrences) {
                add(
                    AgendaEntry(
                        key = "event:${occurrence.key}",
                        recordId = occurrence.eventId,
                        kind = AgendaKind.FIXED_EVENT,
                        title = occurrence.title,
                        time = occurrence.startsAt,
                        endsAt = occurrence.endsAt,
                        workspace = workspaceLabel(occurrence.workspaceId),
                    ),
                )
            }
            for (session in ownerSessions) {
                val task = resolveSessionTask(session.taskId)
                add(
                    AgendaEntry(
                        key = "session:${session.id}",
                        recordId = session.id,
                        kind = AgendaKind.WORK_SESSION,
                        title = task?.title ?: "个人工作时段",
                        time = session.startsAt,
                        endsAt = session.endsAt,
                        workspace = workspaceLabel(task?.workspaceId),
                        completed = session.status == WorkSessionStatus.COMPLETED,
                    ),
                )
            }
            for (task in ownerTasks) {
                val resolvedDeadline = task.deadline
                if (resolvedDeadline == TaskDeadline.None) continue
                val time = resolvedDeadline.sortKeyMillis(zone)
                if (time >= window.start && time < window.endExclusive) {
                    add(
                        AgendaEntry(
                            key = "task:${task.id}",
                            recordId = task.id,
                            kind = AgendaKind.TASK_DEADLINE,
                            title = task.title,
                            time = time,
                            dateOnly = resolvedDeadline is TaskDeadline.DateOnly,
                            workspace = workspaceLabel(task.workspaceId),
                            completed = task.status == TaskStatus.COMPLETED,
                        ),
                    )
                }
            }
        }.sortedWith(compareBy(AgendaEntry::time, { it.kind.ordinal }, AgendaEntry::key))

        return HomeAgendaFeed(
            agenda = AgendaSnapshot(window, entries),
            activeWorkspaces = activeWorkspaces,
        )
    }

    fun observe(
        windowFlow: Flow<AgendaWindow>,
        zone: LocalZone = defaultZone,
    ): Flow<AgendaSnapshot> = combine(windowFlow, revisionSignal()) { window, _ ->
        invoke(window, zone)
    }

    fun observeFeed(
        todayFlow: Flow<String>,
        zone: LocalZone = defaultZone,
    ): Flow<HomeAgendaFeed> = combine(todayFlow, revisionSignal()) { today, _ ->
        loadFeed(today, zone)
    }

    private fun revisionSignal(): Flow<List<Long>> {
        val flows = listOf(
            taskRepository.revision,
            fixedEventRepository.revision,
            workSessionRepository.revision,
        ).distinct()
        return combine(flows) { values -> values.toList() }.distinctUntilChanged()
    }
}
