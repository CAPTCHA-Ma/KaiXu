package com.kaixu.domain.usecase.scheduling

import com.kaixu.core.platform.CalendarDate
import com.kaixu.core.platform.LocalZone
import com.kaixu.data.datasource.FixedEventDataSource
import com.kaixu.data.datasource.TaskDataSource
import com.kaixu.data.datasource.WorkSessionDataSource
import com.kaixu.data.datasource.WorkspaceDataSource
import com.kaixu.data.repository.DefaultFixedEventRepository
import com.kaixu.data.repository.DefaultTaskRepository
import com.kaixu.data.repository.DefaultWorkSessionRepository
import kotlin.math.abs

enum class AgendaKind { FIXED_EVENT, WORK_SESSION, TASK_DEADLINE }

/** time 是排序时刻；任务使用截止时刻，不代表开始执行。dateOnly 保留日期精度。 */
data class AgendaEntry(
    val key: String,
    val recordId: String,
    val kind: AgendaKind,
    val title: String,
    val time: Long,
    val endsAt: Long? = null,
    val dateOnly: Boolean = false,
    val workspace: String? = null,
    val completed: Boolean = false,
) {
    fun isOngoing(now: Long): Boolean = !completed && endsAt != null && time <= now && now < endsAt
}

data class AgendaWindow(val today: String, val start: Long, val endExclusive: Long) {
    companion object {
        fun around(
            today: String,
            zone: LocalZone = LocalZone.systemDefault(),
        ): AgendaWindow {
            require(CalendarDate.isValidDate(today)) { "日期无效：$today" }
            return AgendaWindow(
                today = today,
                start = zone.startOfDayMillis(CalendarDate.plusDays(today, -1L)),
                endExclusive = zone.startOfDayMillis(CalendarDate.plusDays(today, 2L)),
            )
        }
    }
}

data class AgendaSnapshot(val window: AgendaWindow, val entries: List<AgendaEntry>) {
    /** 优先正在发生的安排，否则选择距离现在最近的未完成事项；没有时再回看历史。 */
    fun nearestIndex(now: Long): Int? {
        val candidates = entries.indices.filter { !entries[it].completed }.ifEmpty { entries.indices.toList() }
        return candidates.minWithOrNull(compareBy<Int>(
            { if (entries[it].isOngoing(now)) 0 else 1 },
            {
                val entry = entries[it]
                if (entry.isOngoing(now)) 0L else minOf(abs(entry.time - now), abs((entry.endsAt ?: entry.time) - now))
            },
            { entries[it].time },
        ))
    }
}

/** 兼容入口：包装既有数据源为仓储并统一委托至 [QueryHomeAgendaUseCase]。 */
class AgendaUseCase(
    private val tasks: TaskDataSource,
    private val events: FixedEventDataSource,
    private val sessions: WorkSessionDataSource,
    private val workspaces: WorkspaceDataSource,
) {
    suspend operator fun invoke(
        ownerUserId: String,
        window: AgendaWindow,
        zone: LocalZone = LocalZone.of("Asia/Shanghai"),
    ): AgendaSnapshot {
        require(ownerUserId.isNotBlank())
        val taskRepo = DefaultTaskRepository(
            source = tasks,
            workspaces = workspaces,
            workSessions = sessions,
            ownerUserId = ownerUserId,
        )
        val eventRepo = DefaultFixedEventRepository(
            source = events,
            workspaces = workspaces,
            workSessions = sessions,
            tasks = tasks,
            ownerUserId = ownerUserId,
        )
        val sessionRepo = DefaultWorkSessionRepository(
            source = sessions,
            tasks = tasks,
            events = events,
            workspaces = workspaces,
            ownerUserId = ownerUserId,
        )
        return QueryHomeAgendaUseCase(taskRepo, eventRepo, sessionRepo, zone)(window, zone)
    }
}
