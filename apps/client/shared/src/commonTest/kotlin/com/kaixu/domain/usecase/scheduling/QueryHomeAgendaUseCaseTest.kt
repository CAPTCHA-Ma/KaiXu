package com.kaixu.domain.usecase.scheduling

import com.kaixu.core.platform.LocalZone
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.tasks.TaskDeadline
import com.kaixu.domain.model.tasks.TaskStatus
import com.kaixu.domain.model.tasks.withDeadline
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceKind
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.repository.LocalDataRevisionTracker
import com.kaixu.testing.TestFixedEventRepository
import com.kaixu.testing.TestTaskRepository
import com.kaixu.testing.TestWorkSessionRepository
import com.kaixu.testing.TestWorkSessionStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class QueryHomeAgendaUseCaseTest {
    private val owner = "alice"
    private val shanghai = LocalZone.of("Asia/Shanghai")
    private val utc = LocalZone.of("UTC")

    private val revisionTracker = LocalDataRevisionTracker()
    private val taskRepo = TestTaskRepository(ownerUserId = owner, revisionTracker = revisionTracker)
    private val eventRepo = TestFixedEventRepository(ownerUserId = owner, revisionTracker = revisionTracker)
    private val sessionStore = TestWorkSessionStore()
    private val sessionRepo = TestWorkSessionRepository(
        store = sessionStore,
        ownerUserId = owner,
        revisionTracker = revisionTracker,
    )
    private val useCase = QueryHomeAgendaUseCase(
        taskRepository = taskRepo,
        fixedEventRepository = eventRepo,
        workSessionRepository = sessionRepo,
        defaultZone = shanghai,
    )

    private fun at(date: String, hour: Int, minute: Int = 0, zone: LocalZone = shanghai): Long =
        zone.atLocalDateTimeMillis(date, (hour * 60L + minute) * 60_000L)

    private fun workspace(
        id: String,
        name: String,
        kind: WorkspaceKind = WorkspaceKind.COURSE,
        status: WorkspaceStatus = WorkspaceStatus.ACTIVE,
        ownerId: String = owner,
    ) = Workspace(
        id = id,
        ownerUserId = ownerId,
        kind = kind,
        name = name,
        description = null,
        linkedCourseId = null,
        status = status,
        createdAt = 0L,
        updatedAt = 0L,
        archivedAt = if (status == WorkspaceStatus.ARCHIVED) 1L else null,
    )

    private fun registerWorkspace(ws: Workspace) {
        taskRepo.putWorkspace(ws)
        eventRepo.putWorkspace(ws)
        sessionStore.putWorkspace(ws)
    }

    private suspend fun registerTask(task: Task): Task {
        taskRepo.create(task)
        sessionStore.putTask(task)
        return task
    }

    @Test
    fun rejectsMismatchedRepositoryOwners() {
        val foreignSessionRepo = TestWorkSessionRepository(ownerUserId = "bob")
        assertFailsWith<IllegalArgumentException> {
            QueryHomeAgendaUseCase(taskRepo, eventRepo, foreignSessionRepo, shanghai)
        }
    }

    @Test
    fun aggregatesRecurringEventsOverridesSessionsAndTaskDeadlinesWithPrivacyAndZones() = runTest {
        val activeCourse = workspace("w-course", "编译原理", WorkspaceKind.COURSE, WorkspaceStatus.ACTIVE)
        val activeProject = workspace("w-proj", "毕业设计", WorkspaceKind.PROJECT, WorkspaceStatus.ACTIVE)
        val archivedSpace = workspace("w-arch", "大二物理", WorkspaceKind.COURSE, WorkspaceStatus.ARCHIVED)
        val foreignSpace = workspace("w-bob", "他人空间", WorkspaceKind.COURSE, WorkspaceStatus.ACTIVE, ownerId = "bob")
        listOf(activeCourse, activeProject, archivedSpace, foreignSpace).forEach(::registerWorkspace)

        val dateOnlyTask = registerTask(
            Task(id = "t-date", title = "仅日期作业", createdBy = owner, workspaceId = archivedSpace.id)
                .withDeadline(TaskDeadline.DateOnly("2026-11-14")),
        )
        val completedExactTask = registerTask(
            Task(
                id = "t-done",
                title = "已完成任务",
                createdBy = owner,
                workspaceId = activeCourse.id,
                status = TaskStatus.COMPLETED,
                completedAt = at("2026-11-14", 8),
            ).withDeadline(TaskDeadline.ExactTime(at("2026-11-14", 18, 0))),
        )
        val assignedTask = registerTask(
            Task(id = "t-assigned", title = "指派任务", createdBy = "bob", assigneeUserId = owner)
                .withDeadline(TaskDeadline.ExactTime(at("2026-11-15", 10, 0))),
        )
        val noDeadlineTask = registerTask(Task(id = "t-none", title = "无截止任务", createdBy = owner))
        val outOfWindowTask = registerTask(
            Task(id = "t-out", title = "窗口外截止", createdBy = owner)
                .withDeadline(TaskDeadline.ExactTime(at("2026-11-16", 0, 0))),
        )
        val foreignTask = registerTask(
            Task(id = "t-foreign", title = "他人截止", createdBy = "bob")
                .withDeadline(TaskDeadline.DateOnly("2026-11-14")),
        )

        // 本人每周五六日 09:00-10:00 重复事件；11-14 取消占位，11-15 改期至 16:00-17:30
        val series = FixedEvent(
            id = "e-series",
            title = "晨读研讨",
            startsAt = at("2026-11-13", 9, 0),
            endsAt = at("2026-11-13", 10, 0),
            workspaceId = activeCourse.id,
            createdBy = owner,
            repeatWeekdays = setOf(5, 6, 7),
            repeatUntil = "2026-11-16",
        )
        val cancelledSat = FixedEvent(
            id = "e-cancel-sat",
            title = "晨读研讨",
            startsAt = at("2026-11-14", 9, 0),
            endsAt = at("2026-11-14", 10, 0),
            createdBy = owner,
            seriesId = series.id,
            originalDate = "2026-11-14",
            isOverride = true,
            isCancelled = true,
        )
        val movedSun = FixedEvent(
            id = "e-moved-sun",
            title = "周日加练",
            startsAt = at("2026-11-15", 16, 0),
            endsAt = at("2026-11-15", 17, 30),
            workspaceId = activeProject.id,
            createdBy = owner,
            seriesId = series.id,
            originalDate = "2026-11-15",
            isOverride = true,
        )
        // 他人在我方工作区创建的共享事件：首页只聚合本人固定事件，不混入他人事件
        val sharedOtherEvent = FixedEvent(
            id = "e-shared-bob",
            title = "他人共享讲座",
            startsAt = at("2026-11-14", 10, 0),
            endsAt = at("2026-11-14", 11, 0),
            workspaceId = activeCourse.id,
            createdBy = "bob",
        )
        listOf(series, cancelledSat, movedSun, sharedOtherEvent).forEach(eventRepo::putEvent)

        // 本人已完成时段、跨零点时段、脱链时段，以及应排除的已取消/他人时段
        val completedSession = WorkSession(
            id = "ws-done",
            taskId = dateOnlyTask.id,
            startsAt = at("2026-11-13", 20, 0),
            endsAt = at("2026-11-13", 21, 0),
            ownerUserId = owner,
            status = WorkSessionStatus.COMPLETED,
        )
        val detachedSession = WorkSession(
            id = "ws-detached",
            taskId = null,
            startsAt = at("2026-11-14", 14, 0),
            endsAt = at("2026-11-14", 15, 0),
            ownerUserId = owner,
            detachedAt = at("2026-11-14", 12, 0),
            detachReason = "TASK_DELETED",
        )
        val cancelledSession = WorkSession(
            id = "ws-cancel",
            taskId = dateOnlyTask.id,
            startsAt = at("2026-11-14", 11, 0),
            endsAt = at("2026-11-14", 12, 0),
            ownerUserId = owner,
            status = WorkSessionStatus.CANCELLED,
        )
        val foreignSession = WorkSession(
            id = "ws-foreign",
            taskId = dateOnlyTask.id,
            startsAt = at("2026-11-14", 11, 0),
            endsAt = at("2026-11-14", 12, 0),
            ownerUserId = "bob",
        )
        listOf(completedSession, detachedSession, cancelledSession, foreignSession).forEach(sessionStore::putWorkSession)

        val feed = useCase.loadFeed("2026-11-14", shanghai)
        assertEquals(listOf(activeCourse.id, activeProject.id), feed.activeWorkspaces.map { it.id })

        val entries = feed.agenda.entries
        assertEquals(entries.map { it.time }.sorted(), entries.map { it.time })

        // 排除无截止、窗口外、他人任务、共享他人事件、已取消时段与他人时段
        val recordIds = entries.map { it.recordId }.toSet()
        assertFalse(noDeadlineTask.id in recordIds)
        assertFalse(outOfWindowTask.id in recordIds)
        assertFalse(foreignTask.id in recordIds)
        assertFalse(sharedOtherEvent.id in recordIds)
        assertFalse(cancelledSession.id in recordIds)
        assertFalse(foreignSession.id in recordIds)

        // 固定事件：11-13 展开 + 11-15 改期例外，11-14 取消占位不出现
        val eventEntries = entries.filter { it.kind == AgendaKind.FIXED_EVENT }
        assertEquals(listOf("event:${series.id}@2026-11-13", "event:${series.id}@2026-11-15"), eventEntries.map { it.key })
        assertEquals("周日加练", eventEntries[1].title)
        assertEquals("毕业设计", eventEntries[1].workspace)

        // 工作时段：保留已完成（completed=true）与脱链回退标题（"个人工作时段"）
        val sessionEntries = entries.filter { it.kind == AgendaKind.WORK_SESSION }
        assertEquals(listOf("session:ws-done", "session:ws-detached"), sessionEntries.map { it.key })
        assertTrue(sessionEntries[0].completed)
        assertEquals("大二物理 · 已归档", sessionEntries[0].workspace)
        assertEquals("个人工作时段", sessionEntries[1].title)
        assertNull(sessionEntries[1].workspace)

        // 任务截止：DateOnly 落在上海当日末尾且 dateOnly=true，已完成任务保留 completed=true
        val deadlineEntries = entries.filter { it.kind == AgendaKind.TASK_DEADLINE }
        assertEquals(listOf(completedExactTask.id, dateOnlyTask.id, assignedTask.id), deadlineEntries.map { it.recordId })
        assertTrue(deadlineEntries[0].completed)
        assertFalse(deadlineEntries[0].dateOnly)
        assertTrue(deadlineEntries[1].dateOnly)
        assertEquals("大二物理 · 已归档", deadlineEntries[1].workspace)
        assertEquals(at("2026-11-15", 0, 0) - 1L, deadlineEntries[1].time)

        // 切换为 UTC 时区时，仅日期截止自动对齐 UTC 当日末尾（晚 8 小时）
        val utcFeed = useCase.loadFeed("2026-11-14", utc)
        val utcDateEntry = utcFeed.agenda.entries.single { it.recordId == dateOnlyTask.id }
        assertEquals(at("2026-11-15", 0, 0, utc) - 1L, utcDateEntry.time)
    }

    @Test
    fun observeFeedDeduplicatesSharedRevisionAndReactsToTaskEventAndSessionMutations() = runTest {
        val todayFlow = MutableStateFlow("2026-11-14")
        val emissions = mutableListOf<HomeAgendaFeed>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.observeFeed(todayFlow, shanghai).collect { emissions += it }
        }

        // 1. 初始发射为空
        assertEquals(1, emissions.size)
        assertTrue(emissions.last().agenda.entries.isEmpty())

        // 2. 创建任务（共用同一 LocalDataRevisionTracker，应只触发一次重算而不是三次）
        val task = registerTask(
            Task(id = "t-1", title = "算法复习", createdBy = owner)
                .withDeadline(TaskDeadline.ExactTime(at("2026-11-14", 20, 0))),
        )
        assertEquals(2, emissions.size)
        assertEquals(listOf("task:t-1"), emissions.last().agenda.entries.map { it.key })

        // 3. 创建固定事件触发一次重算
        eventRepo.create(
            FixedEvent(
                id = "e-1",
                title = "编译课",
                startsAt = at("2026-11-14", 9, 0),
                endsAt = at("2026-11-14", 10, 30),
                createdBy = owner,
            ),
        )
        assertEquals(3, emissions.size)
        assertEquals(listOf("event:e-1", "task:t-1"), emissions.last().agenda.entries.map { it.key })

        // 4. 创建个人工作时段触发一次重算
        sessionRepo.createAll(
            listOf(
                WorkSession(
                    id = "ws-1",
                    taskId = task.id,
                    startsAt = at("2026-11-14", 14, 0),
                    endsAt = at("2026-11-14", 15, 0),
                    ownerUserId = owner,
                ),
            ),
        )
        assertEquals(4, emissions.size)
        assertEquals(listOf("event:e-1", "session:ws-1", "task:t-1"), emissions.last().agenda.entries.map { it.key })

        // 5. 切换窗口日期自动重新加载对应三日区间
        todayFlow.value = "2026-11-20"
        assertEquals(5, emissions.size)
        assertEquals("2026-11-20", emissions.last().agenda.window.today)
        assertTrue(emissions.last().agenda.entries.isEmpty())

        job.cancel()
    }
}
