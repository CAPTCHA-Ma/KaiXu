package com.kaixu.domain.usecase.workspaces

import com.kaixu.data.datasource.FixedEventDataSource
import com.kaixu.data.datasource.TaskDataSource
import com.kaixu.data.datasource.WorkSessionDataSource
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.tasks.TaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WorkspaceOverviewUseCaseTest {
    // 上海 2026-11-08 09:00，近期窗口到 11-15 00:00，不把日期截止当成当天零点。
    private val window = WorkspaceDeadlineWindow("2026-11-08", 1_794_099_600_000L, "2026-11-15", 1_794_672_000_000L)

    @Test
    fun preservesOriginalRecordsAndIncludesOnlyWorkspaceDataAndOwnSessions() = runTest {
        val source = ReadOnlySources()
        val current = Task("t", "当前任务", workspaceId = "course")
        val foreign = Task("other", "其他工作区", workspaceId = "other")
        val independent = Task("independent", "独立任务")
        source.tasks = listOf(current, foreign, independent)
        val event = FixedEvent("e", "当前课程", 10, 20, workspaceId = "course")
        source.events = listOf(event, event.copy(id = "other-event", workspaceId = "other"), event.copy(id = "independent-event", workspaceId = null))
        val mine = WorkSession("mine", current.id, 30, 40, ownerUserId = "me")
        source.sessions = listOf(
            mine,
            mine.copy(id = "theirs", ownerUserId = "another"),
            mine.copy(id = "detached", taskId = null),
            mine.copy(id = "independent-session", taskId = independent.id),
            mine.copy(id = "foreign-session", taskId = foreign.id),
        )

        val result = source.useCase()("course", "me", window)

        assertSame(current, result.tasks.single())
        assertSame(event, result.fixedEvents.single())
        assertSame(mine, result.workSessions.single())
        assertEquals(listOf("course"), source.taskQueries)
        assertEquals(listOf("course"), source.eventQueries)
        assertEquals(listOf(current, foreign, independent), source.tasks)
        assertEquals(1, result.allTaskCount)
    }

    @Test
    fun calendarDatesAndExactTimesRespectShanghaiDayAndExclusiveSeventhDayEnd() = runTest {
        val source = ReadOnlySources()
        source.tasks = listOf(
            task("yesterday", date = "2026-11-07"),
            task("today", date = "2026-11-08"),
            task("last-day", date = "2026-11-14"),
            task("next-week", date = "2026-11-15"),
            task("earlier-today", at = window.nowMillis - 1),
            task("right-now", at = window.nowMillis),
            task("last-instant", at = window.upcomingEndMillisExclusive - 1),
            task("next-week-instant", at = window.upcomingEndMillisExclusive),
            task("no-date"),
        )
        val query = source.useCase()

        assertEquals(setOf("yesterday", "earlier-today"), query("course", "me", window, deadline = WorkspaceDeadlineFilter.OVERDUE).tasks.ids())
        assertEquals(setOf("today", "last-day", "right-now", "last-instant"), query("course", "me", window, deadline = WorkspaceDeadlineFilter.NEXT_SEVEN_DAYS).tasks.ids())
        assertEquals(setOf("no-date"), query("course", "me", window, deadline = WorkspaceDeadlineFilter.NO_DEADLINE).tasks.ids())
    }

    @Test
    fun combinesFiltersWhileCountsUpcomingSummaryAndScheduleUseAllWorkspaceTasks() = runTest {
        val source = ReadOnlySources()
        source.tasks = listOf(
            task("todo", date = "2026-11-08"),
            task("in-progress", date = "2026-11-09").copy(status = TaskStatus.IN_PROGRESS),
            task("done", date = "2026-11-08").copy(status = TaskStatus.COMPLETED, completedAt = window.nowMillis),
            task("undated"),
        )
        source.sessions = listOf(WorkSession("s", "in-progress", 10, 20, ownerUserId = "me"))

        val result = source.useCase()("course", "me", window, status = TaskStatus.TODO, deadline = WorkspaceDeadlineFilter.NEXT_SEVEN_DAYS)

        assertEquals(setOf("todo"), result.tasks.ids())
        assertEquals(4, result.allTaskCount)
        assertEquals(3, result.incompleteTaskCount)
        assertEquals(1, result.completedTaskCount)
        assertEquals(setOf("todo", "in-progress"), result.upcomingTasks.ids())
        assertEquals("s", result.workSessions.single().id)
    }

    @Test
    fun refreshReturnsTheSameIdsWithUpdatedContentAndDoesNotCacheResults() = runTest {
        val source = ReadOnlySources()
        val original = task("task", date = "2026-11-08")
        source.tasks = listOf(original)
        val query = source.useCase()
        assertSame(original, query("course", "me", window).tasks.single())

        val updated = original.copy(title = "全局详情修改后的标题", status = TaskStatus.IN_PROGRESS)
        source.tasks = listOf(updated)
        val refreshed = query("course", "me", window).tasks.single()
        assertSame(updated, refreshed)
        assertEquals(original.id, refreshed.id)
    }

    @Test
    fun failuresAndCancellationPropagateForTheCallerToHandleAndRetry() = runTest {
        val source = ReadOnlySources()
        val query = source.useCase()
        source.failure = IllegalStateException("读取失败")
        assertFailsWith<IllegalStateException> { query("course", "me", window) }
        source.failure = CancellationException("离开页面")
        assertFailsWith<CancellationException> { query("course", "me", window) }
        source.failure = null
        assertTrue(query("course", "me", window).tasks.isEmpty())
    }

    private fun task(id: String, date: String? = null, at: Long? = null) =
        Task(id, id, workspaceId = "course", deadlineDate = date, deadlineAt = at)

    private fun List<Task>.ids() = map { it.id }.toSet()

    /** 刻意让读取返回越界记录，验证聚合入口仍检查工作区与本人；任何写调用都会让测试失败。 */
    private class ReadOnlySources : TaskDataSource, FixedEventDataSource, WorkSessionDataSource {
        var tasks = emptyList<Task>()
        var events = emptyList<FixedEvent>()
        var sessions = emptyList<WorkSession>()
        var failure: Exception? = null
        val taskQueries = mutableListOf<String>()
        val eventQueries = mutableListOf<String>()

        fun useCase() = WorkspaceOverviewUseCase(this, this, this)

        override suspend fun listTasksByWorkspace(workspaceId: String): List<Task> {
            failure?.let { throw it }
            taskQueries += workspaceId
            return tasks
        }
        override suspend fun listEventsByWorkspace(workspaceId: String): List<FixedEvent> {
            eventQueries += workspaceId
            return events
        }
        override suspend fun listWorkSessions() = sessions

        override suspend fun listTasks(): List<Task> = error("应按工作区查询任务")
        override suspend fun findTaskById(id: String): Task? = error("此测试无需详情查询")
        override suspend fun createTask(title: String): Task = error("概览不能创建任务")
        override suspend fun createTask(value: Task): Task = error("概览不能创建任务")
        override suspend fun updateTask(value: Task): Boolean = error("概览不能更新任务")
        override suspend fun deleteTaskById(id: String): Boolean = error("概览不能删除任务")
        override suspend fun listEvents(): List<FixedEvent> = error("应按工作区查询事件")
        override suspend fun findEventById(id: String): FixedEvent? = error("此测试无需详情查询")
        override suspend fun createEvent(value: FixedEvent): FixedEvent = error("概览不能创建事件")
        override suspend fun updateEvent(value: FixedEvent): Boolean = error("概览不能更新事件")
        override suspend fun deleteEventById(id: String): Boolean = error("概览不能删除事件")
        override suspend fun listEventsInRange(ownerId: String, start: Long, end: Long): List<FixedEvent> = error("此测试使用工作区事件")
        override suspend fun findWorkSessionById(id: String): WorkSession? = error("此测试无需详情查询")
        override suspend fun createWorkSession(value: WorkSession): WorkSession = error("概览不能创建时段")
        override suspend fun createWorkSessions(values: List<WorkSession>): List<WorkSession> = error("概览不能创建时段")
        override suspend fun listWorkSessionsByOwnerAndTask(ownerId: String, taskId: String): List<WorkSession> = error("此测试批量读取时段")
        override suspend fun updateWorkSession(value: WorkSession): Boolean = error("概览不能更新时段")
        override suspend fun deleteWorkSessionById(id: String): Boolean = error("概览不能删除时段")
        override suspend fun listWorkSessionsByTask(taskId: String): List<WorkSession> = error("此测试批量读取时段")
        override suspend fun listWorkSessionsInRange(ownerId: String, start: Long, end: Long): List<WorkSession> = error("此测试读取全部时段")
    }
}
