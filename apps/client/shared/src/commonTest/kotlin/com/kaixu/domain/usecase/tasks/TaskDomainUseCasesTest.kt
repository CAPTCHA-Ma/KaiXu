package com.kaixu.domain.usecase.tasks

import com.kaixu.core.platform.LocalZone
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.model.tasks.CompleteTaskResult
import com.kaixu.domain.model.tasks.FutureSessionPolicy
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.tasks.TaskDeadline
import com.kaixu.domain.model.tasks.TaskDeadlineFilter
import com.kaixu.domain.model.tasks.TaskDraft
import com.kaixu.domain.model.tasks.TaskPriority
import com.kaixu.domain.model.tasks.TaskQuery
import com.kaixu.domain.model.tasks.TaskScheduleState
import com.kaixu.domain.model.tasks.TaskSortOrder
import com.kaixu.domain.model.tasks.TaskStatus
import com.kaixu.domain.model.tasks.TaskWorkspaceFilter
import com.kaixu.domain.model.tasks.deadline
import com.kaixu.domain.model.tasks.toDraft
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceKind
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.testing.TestTaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TaskDomainUseCasesTest {
    private val shanghai = LocalZone.of("Asia/Shanghai")
    private val now = shanghai.startOfDayMillis("2026-11-25") + 15L * 3_600_000L // 2026-11-25 15:00 +08:00

    private fun newFixture(): Pair<TestTaskRepository, TaskUseCases> {
        val repo = TestTaskRepository(ownerUserId = "alice")
        return repo to TaskUseCases(repo, defaultZone = shanghai)
    }

    @Test
    fun queryTasksFiltersSortsCountsAndDistinguishesScheduleAndOverdueStates() = runTest {
        val (repo, useCases) = newFixture()
        repo.putWorkspace(
            Workspace("ws-active", "alice", WorkspaceKind.COURSE, "软件工程", null, null, WorkspaceStatus.ACTIVE, 1L, 1L, null),
        )
        repo.putWorkspace(
            Workspace("ws-archived", "alice", WorkspaceKind.PROJECT, "旧项目", null, null, WorkspaceStatus.ARCHIVED, 1L, 1L, 2L),
        )
        repo.putWorkspace(
            Workspace("ws-foreign", "bob", WorkspaceKind.PROJECT, "他人项目", null, null, WorkspaceStatus.ACTIVE, 1L, 1L, null),
        )

        val noDdl = repo.create(
            Task(id = "t-no-ddl", title = "独立无截止", createdBy = "alice", priority = TaskPriority.HIGH),
        )
        val overdue = repo.create(
            Task(
                id = "t-overdue",
                title = "昨日逾期任务",
                workspaceId = "ws-active",
                createdBy = "alice",
                status = TaskStatus.IN_PROGRESS,
                priority = TaskPriority.NORMAL,
                deadlineDate = "2026-11-24",
            ),
        )
        val todayExact = repo.create(
            Task(
                id = "t-today",
                title = "今日截止任务",
                description = "包含重点关键字",
                workspaceId = "ws-active",
                createdBy = "alice",
                status = TaskStatus.TODO,
                priority = TaskPriority.HIGH,
                deadlineAt = shanghai.startOfDayMillis("2026-11-25") + 18L * 3_600_000L,
            ),
        )
        val archivedDone = repo.create(
            Task(
                id = "t-archived-done",
                title = "已归档完成任务",
                workspaceId = "ws-archived",
                createdBy = "alice",
                status = TaskStatus.COMPLETED,
                completedAt = now - 1000L,
                deadlineDate = "2026-11-20",
            ),
        )
        // 他人任务不应出现在本人任务列表
        repo.create(Task(id = "t-foreign", title = "他人独立", createdBy = "bob"))
        repo.create(Task(id = "t-foreign-ws", title = "他人工作区", createdBy = "bob", workspaceId = "ws-foreign"))

        // 仅存在已取消或他人时段时，任务仍视为未排期
        repo.putWorkSession(WorkSession("s-cancel", noDdl.id, now + 1000L, now + 2000L, ownerUserId = "alice", status = WorkSessionStatus.CANCELLED))
        repo.putWorkSession(WorkSession("s-bob", noDdl.id, now + 1000L, now + 2000L, ownerUserId = "bob", status = WorkSessionStatus.PLANNED))
        repo.putWorkSession(WorkSession("s-overdue", overdue.id, now + 3600_000L, now + 7200_000L, ownerUserId = "alice", status = WorkSessionStatus.PLANNED))
        repo.putWorkSession(WorkSession("s-done", archivedDone.id, now - 7200_000L, now - 3600_000L, ownerUserId = "alice", status = WorkSessionStatus.COMPLETED))

        val defaultSnapshot = useCases.queryTasks(TaskQuery(), nowMillis = now)
        assertEquals(listOf("t-overdue", "t-today", "t-no-ddl"), defaultSnapshot.items.map { it.task.id })
        assertEquals(4, defaultSnapshot.counts.totalCount)
        assertEquals(3, defaultSnapshot.counts.incompleteCount)
        assertEquals(1, defaultSnapshot.counts.completedCount)
        assertEquals(1, defaultSnapshot.counts.overdueCount)
        assertEquals(1, defaultSnapshot.counts.noDeadlineCount)
        assertEquals(2, defaultSnapshot.counts.unscheduledCount)

        val overdueItem = defaultSnapshot.items.first { it.task.id == overdue.id }
        assertTrue(overdueItem.isOverdue)
        assertEquals(TaskScheduleState.HAS_SESSIONS, overdueItem.scheduleState)
        assertEquals(1, overdueItem.activeSessionCount)
        assertEquals("软件工程", overdueItem.workspaceName)
        assertTrue(overdueItem.canEdit)

        val noDdlItem = defaultSnapshot.items.first { it.task.id == noDdl.id }
        assertFalse(noDdlItem.isOverdue)
        assertEquals(TaskScheduleState.UNSCHEDULED, noDdlItem.scheduleState)
        assertEquals(0, noDdlItem.activeSessionCount)

        // 工作区筛选 + 优先级排序 + 已完成/归档可见与计数稳定
        val allStatuses = setOf(TaskStatus.TODO, TaskStatus.IN_PROGRESS, TaskStatus.COMPLETED)
        val prioritySnapshot = useCases.queryTasks(
            TaskQuery(
                statuses = allStatuses,
                sortOrder = TaskSortOrder.PRIORITY_DESC,
            ),
            nowMillis = now,
        )
        assertEquals(
            listOf("t-today", "t-no-ddl", "t-archived-done", "t-overdue"),
            prioritySnapshot.items.map { it.task.id },
        )
        val archivedItem = prioritySnapshot.items.first { it.task.id == archivedDone.id }
        assertTrue(archivedItem.workspaceArchived)
        assertFalse(archivedItem.canEdit)
        assertFalse(archivedItem.isOverdue)
        assertEquals(TaskScheduleState.HAS_SESSIONS, archivedItem.scheduleState)

        // 关键词与截止筛选不破坏所在工作区范围统计
        val filteredByKeyword = useCases.queryTasks(
            TaskQuery(
                workspaceFilter = TaskWorkspaceFilter.Workspace("ws-active"),
                statuses = allStatuses,
                deadlineFilter = TaskDeadlineFilter.Today,
                keyword = "重点",
            ),
            nowMillis = now,
        )
        assertEquals(listOf(todayExact.id), filteredByKeyword.items.map { it.task.id })
        assertEquals(2, filteredByKeyword.counts.totalCount)
        assertEquals(1, filteredByKeyword.counts.overdueCount)

        val standalone = useCases.queryTasks(
            TaskQuery(workspaceFilter = TaskWorkspaceFilter.Standalone, sortOrder = TaskSortOrder.DEFAULT_RECENT),
            nowMillis = now,
        )
        assertEquals(listOf(noDdl.id), standalone.items.map { it.task.id })

        // 响应式 observe 在修订号变化后自动重查
        val queryFlow = MutableStateFlow(TaskQuery(workspaceFilter = TaskWorkspaceFilter.Standalone))
        val observed = mutableListOf<List<String>>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            useCases.queryTasks.observe(queryFlow, clock = { now }).collect { snapshot ->
                observed += snapshot.items.map { it.task.id }
            }
        }
        val created = useCases.createFromDraft(TaskDraft(title = "新独立任务"), id = "t-new")
        useCases.complete(created.id, nowMillis = now)
        job.cancel()
        assertEquals(
            listOf(
                listOf("t-no-ddl"),
                listOf("t-no-ddl", "t-new"),
                listOf("t-no-ddl"),
            ),
            observed,
        )
    }

    @Test
    fun getTaskDetailReturnsContextAndSplitsFuturePlannedSessions() = runTest {
        val (repo, useCases) = newFixture()
        repo.putWorkspace(
            Workspace("ws-active", "alice", WorkspaceKind.COURSE, "高数", null, null, WorkspaceStatus.ACTIVE, 1L, 1L, null),
        )
        repo.putWorkspace(
            Workspace("ws-archived", "alice", WorkspaceKind.PROJECT, "归档项目", null, null, WorkspaceStatus.ARCHIVED, 1L, 1L, 2L),
        )

        val task = repo.create(
            Task(id = "t1", title = "作业", workspaceId = "ws-active", createdBy = "alice", deadlineDate = "2026-11-24"),
        )
        val archivedTask = repo.create(
            Task(id = "t-arch", title = "归档任务", workspaceId = "ws-archived", createdBy = "alice"),
        )
        val foreignTask = repo.create(Task(id = "t-bob", title = "他人任务", createdBy = "bob"))

        repo.putWorkSession(WorkSession("s-past-plan", task.id, now - 7200_000L, now - 3600_000L, ownerUserId = "alice", status = WorkSessionStatus.PLANNED))
        repo.putWorkSession(WorkSession("s-past-done", task.id, now - 3600_000L, now - 1800_000L, ownerUserId = "alice", status = WorkSessionStatus.COMPLETED))
        repo.putWorkSession(WorkSession("s-future-plan", task.id, now + 3600_000L, now + 7200_000L, ownerUserId = "alice", status = WorkSessionStatus.PLANNED))
        repo.putWorkSession(WorkSession("s-future-cancel", task.id, now + 7200_000L, now + 10_800_000L, ownerUserId = "alice", status = WorkSessionStatus.CANCELLED))
        repo.putWorkSession(WorkSession("s-bob", task.id, now + 3600_000L, now + 7200_000L, ownerUserId = "bob", status = WorkSessionStatus.PLANNED))

        assertNull(useCases.getDetail("missing", nowMillis = now))
        assertNull(useCases.getDetail(foreignTask.id, nowMillis = now))

        val detail = assertNotNull(useCases.getDetail(task.id, nowMillis = now))
        assertTrue(detail.canEdit)
        assertTrue(detail.isOverdue)
        assertEquals("高数", detail.workspace?.name)
        assertEquals(
            listOf("s-past-plan", "s-past-done", "s-future-plan", "s-future-cancel"),
            detail.workSessions.map { it.id },
        )
        assertEquals(listOf("s-future-plan"), detail.futurePlannedSessions.map { it.id })

        val archivedDetail = assertNotNull(useCases.getDetail(archivedTask.id, nowMillis = now))
        assertFalse(archivedDetail.canEdit)
    }

    @Test
    fun createAndUpdateValidateWorkspacesStatusTransitionsAndOptimisticMerge() = runTest {
        val (repo, useCases) = newFixture()
        repo.putWorkspace(
            Workspace("ws-active", "alice", WorkspaceKind.COURSE, "进行中课程", null, null, WorkspaceStatus.ACTIVE, 1L, 1L, null),
        )
        repo.putWorkspace(
            Workspace("ws-archived", "alice", WorkspaceKind.PROJECT, "已归档项目", null, null, WorkspaceStatus.ARCHIVED, 1L, 1L, 2L),
        )
        repo.putWorkspace(
            Workspace("ws-foreign", "bob", WorkspaceKind.PROJECT, "他人工作区", null, null, WorkspaceStatus.ACTIVE, 1L, 1L, null),
        )

        assertFailsWith<IllegalArgumentException> {
            useCases.createFromDraft(TaskDraft(title = "无效工作区", workspaceId = "missing"))
        }
        assertFailsWith<IllegalArgumentException> {
            useCases.createFromDraft(TaskDraft(title = "归档工作区", workspaceId = "ws-archived"))
        }
        assertFailsWith<IllegalArgumentException> {
            useCases.createFromDraft(TaskDraft(title = "他人工作区", workspaceId = "ws-foreign"))
        }

        val created = useCases.createFromDraft(
            TaskDraft(
                title = "  初稿任务  ",
                description = "  第一版说明  ",
                workspaceId = "ws-active",
                priority = TaskPriority.NORMAL,
                deadline = TaskDeadline.DateOnly("2026-11-30"),
                estimatedMinutes = 60,
                blockedNote = "  等待材料  ",
            ),
            id = "t-edit",
        )
        assertEquals("初稿任务", created.title)

        // 不能把任务移动到归档或他人工作区
        assertFailsWith<IllegalArgumentException> {
            useCases.updateFromDraft(created.id, created.toDraft().copy(workspaceId = "ws-archived"))
        }
        assertFailsWith<IllegalArgumentException> {
            useCases.updateFromDraft(created.id, created.toDraft().copy(workspaceId = "ws-foreign"))
        }

        // 完整直接更新（预期原记录为空）
        val exact = now + 7_200_000L
        val updated = useCases.updateFromDraft(
            taskId = created.id,
            draft = TaskDraft(
                title = "  已调整任务  ",
                description = "  ",
                workspaceId = null,
                priority = TaskPriority.HIGH,
                deadline = TaskDeadline.ExactTime(exact),
                estimatedMinutes = 90,
                blockedNote = "新阻塞",
            ),
            nextStatus = TaskStatus.IN_PROGRESS,
        )
        assertEquals("已调整任务", updated.title)
        assertNull(updated.description)
        assertNull(updated.workspaceId)
        assertEquals(TaskPriority.HIGH, updated.priority)
        assertEquals(TaskDeadline.ExactTime(exact), updated.deadline)
        assertEquals(90, updated.estimatedMinutes)
        assertEquals("新阻塞", updated.blockedNote)
        assertEquals(TaskStatus.IN_PROGRESS, updated.status)

        // 直接编辑不能跳过 CompleteTaskUseCase
        assertFailsWith<IllegalArgumentException> {
            useCases.updateFromDraft(updated.id, updated.toDraft(), nextStatus = TaskStatus.COMPLETED)
        }

        // 乐观字段合并：后台并发修改了优先级与完成状态时，用户只改标题应保留并发更新
        val snapshotBeforeConflict = updated
        val concurrentCompleted = updated.copy(
            priority = TaskPriority.LOW,
            status = TaskStatus.COMPLETED,
            completedAt = now,
        )
        assertTrue(repo.update(concurrentCompleted))
        val merged = useCases.updateFromDraft(
            taskId = updated.id,
            draft = snapshotBeforeConflict.toDraft().copy(title = "仅改标题"),
            expectedOriginal = snapshotBeforeConflict,
        )
        assertEquals("仅改标题", merged.title)
        assertEquals(TaskPriority.LOW, merged.priority)
        assertEquals(TaskStatus.COMPLETED, merged.status)
        assertEquals(now, merged.completedAt)

        // 若同一字段已被并发改写，则必须拒绝覆盖
        assertFailsWith<IllegalArgumentException> {
            useCases.updateFromDraft(
                taskId = updated.id,
                draft = snapshotBeforeConflict.toDraft().copy(title = "覆盖他人标题"),
                expectedOriginal = snapshotBeforeConflict,
            )
        }
    }

    @Test
    fun completeReopenAndDeleteEnforceFutureSessionPolicyAndPermissions() = runTest {
        val (repo, useCases) = newFixture()
        repo.putWorkspace(
            Workspace("ws-active", "alice", WorkspaceKind.COURSE, "课程", null, null, WorkspaceStatus.ACTIVE, 1L, 1L, null),
        )
        repo.putWorkspace(
            Workspace("ws-archived", "alice", WorkspaceKind.PROJECT, "归档", null, null, WorkspaceStatus.ARCHIVED, 1L, 1L, 2L),
        )

        // 无未来工作时段时可直接完成
        val simple = useCases.createFromDraft(TaskDraft(title = "无未来时段"), id = "t-simple")
        repo.putWorkSession(WorkSession("s-past", simple.id, now - 3600_000L, now - 1800_000L, ownerUserId = "alice", status = WorkSessionStatus.PLANNED))
        val simpleResult = assertIs<CompleteTaskResult.Completed>(useCases.complete(simple.id, nowMillis = now))
        assertEquals(TaskStatus.COMPLETED, simpleResult.task.status)
        assertEquals(now, simpleResult.task.completedAt)
        assertEquals(0, simpleResult.retainedFutureCount)
        assertEquals(0, simpleResult.cancelledFutureCount)

        // 存在未来计划时段且未指定策略时，先返回确认快照且不写库或递增 revision
        val planned = useCases.createFromDraft(TaskDraft(title = "有未来时段", workspaceId = "ws-active"), id = "t-planned")
        repo.putWorkSession(WorkSession("s-hist-done", planned.id, now - 7200_000L, now - 3600_000L, ownerUserId = "alice", status = WorkSessionStatus.COMPLETED))
        repo.putWorkSession(WorkSession("s-hist-plan", planned.id, now - 1800_000L, now - 600_000L, ownerUserId = "alice", status = WorkSessionStatus.PLANNED))
        repo.putWorkSession(WorkSession("s-future", planned.id, now + 3600_000L, now + 7200_000L, ownerUserId = "alice", status = WorkSessionStatus.PLANNED))
        repo.putWorkSession(WorkSession("s-other", planned.id, now + 3600_000L, now + 7200_000L, ownerUserId = "bob", status = WorkSessionStatus.PLANNED))

        val revisionBeforeConfirm = useCases.revision.value
        val needsConfirm = assertIs<CompleteTaskResult.NeedsFutureSessionDecision>(
            useCases.complete(planned.id, nowMillis = now, policy = null),
        )
        assertEquals(listOf("s-future"), needsConfirm.futureSessions.map { it.id })
        assertEquals(TaskStatus.TODO, repo.findById(planned.id)?.status)
        assertEquals(revisionBeforeConfirm, useCases.revision.value)

        // 选择 KEEP_FUTURE：完成任务并保留未来时段
        val kept = assertIs<CompleteTaskResult.Completed>(
            useCases.complete(planned.id, nowMillis = now, policy = FutureSessionPolicy.KEEP_FUTURE),
        )
        assertEquals(1, kept.retainedFutureCount)
        assertEquals(0, kept.cancelledFutureCount)
        assertEquals(WorkSessionStatus.PLANNED, repo.storedWorkSession("s-future")?.status)

        // 恢复未完成并校验非法目标状态
        assertFailsWith<IllegalArgumentException> {
            useCases.reopen(planned.id, targetStatus = TaskStatus.COMPLETED)
        }
        val reopened = useCases.reopen(planned.id, targetStatus = TaskStatus.IN_PROGRESS)
        assertEquals(TaskStatus.IN_PROGRESS, reopened.status)
        assertNull(reopened.completedAt)

        // 选择 CANCEL_FUTURE：仅取消本人未来 PLANNED 时段，历史与他人时段不动
        val cancelledResult = assertIs<CompleteTaskResult.Completed>(
            useCases.complete(planned.id, nowMillis = now + 1000L, policy = FutureSessionPolicy.CANCEL_FUTURE),
        )
        assertEquals(0, cancelledResult.retainedFutureCount)
        assertEquals(1, cancelledResult.cancelledFutureCount)
        assertEquals(WorkSessionStatus.CANCELLED, repo.storedWorkSession("s-future")?.status)
        assertEquals(WorkSessionStatus.COMPLETED, repo.storedWorkSession("s-hist-done")?.status)
        assertEquals(WorkSessionStatus.PLANNED, repo.storedWorkSession("s-hist-plan")?.status)
        assertEquals(WorkSessionStatus.PLANNED, repo.storedWorkSession("s-other")?.status)

        // 再次恢复未完成不会重建已取消时段
        useCases.reopen(planned.id)
        assertEquals(WorkSessionStatus.CANCELLED, repo.storedWorkSession("s-future")?.status)

        // 归档工作区与他人任务拒绝完成、重开、删除
        val archivedTask = repo.create(Task(id = "t-arch", title = "归档", workspaceId = "ws-archived", createdBy = "alice"))
        val foreignTask = repo.create(Task(id = "t-bob", title = "他人", createdBy = "bob"))
        assertFailsWith<IllegalArgumentException> { useCases.complete(archivedTask.id, nowMillis = now) }
        assertFailsWith<IllegalArgumentException> { useCases.reopen(archivedTask.id) }
        assertFailsWith<IllegalArgumentException> { useCases.delete(archivedTask.id) }
        assertFailsWith<IllegalArgumentException> { useCases.complete(foreignTask.id, nowMillis = now) }
        assertFailsWith<IllegalArgumentException> { useCases.delete(foreignTask.id) }

        // 删除任务会脱链相关时段，且重复删除返回 false
        assertTrue(useCases.delete(planned.id))
        assertNull(repo.findById(planned.id))
        assertNull(repo.storedWorkSession("s-hist-done")?.taskId)
        assertEquals("TASK_DELETED", repo.storedWorkSession("s-hist-done")?.detachReason)
        assertFalse(useCases.delete(planned.id))
    }
}
