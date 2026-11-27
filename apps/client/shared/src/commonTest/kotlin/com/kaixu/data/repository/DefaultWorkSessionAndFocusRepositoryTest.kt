package com.kaixu.data.repository

import com.kaixu.data.datasource.FixedEventDataSource
import com.kaixu.data.datasource.FocusDataSource
import com.kaixu.data.datasource.TaskDataSource
import com.kaixu.data.datasource.WorkSessionDataSource
import com.kaixu.data.datasource.WorkspaceDataSource
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.FocusDuration
import com.kaixu.domain.model.scheduling.FocusMode
import com.kaixu.domain.model.scheduling.FocusSegment
import com.kaixu.domain.model.scheduling.FocusSession
import com.kaixu.domain.model.scheduling.FocusSessionStatus
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceKind
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.repository.LocalDataRevisionTracker
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultWorkSessionAndFocusRepositoryTest {
    private class FakeWorkSessions : WorkSessionDataSource {
        val rows = linkedMapOf<String, WorkSession>()
        var failNextCreate = false

        override suspend fun listWorkSessions(): List<WorkSession> = rows.values.toList()
        override suspend fun findWorkSessionById(id: String): WorkSession? = rows[id]
        override suspend fun createWorkSession(value: WorkSession): WorkSession {
            if (failNextCreate) {
                failNextCreate = false
                error("创建计划时段失败")
            }
            rows[value.id] = value
            return value
        }

        override suspend fun createWorkSessions(values: List<WorkSession>): List<WorkSession> {
            if (failNextCreate) {
                failNextCreate = false
                error("批量创建计划时段失败")
            }
            values.forEach { rows[it.id] = it }
            return values
        }

        override suspend fun updateWorkSession(value: WorkSession): Boolean {
            if (value.id !in rows) return false
            rows[value.id] = value
            return true
        }

        override suspend fun deleteWorkSessionById(id: String): Boolean = rows.remove(id) != null
        override suspend fun listWorkSessionsByTask(taskId: String): List<WorkSession> =
            rows.values.filter { it.taskId == taskId }

        override suspend fun listWorkSessionsByOwnerAndTask(ownerId: String, taskId: String): List<WorkSession> =
            rows.values.filter { it.ownerUserId == ownerId && it.taskId == taskId }

        override suspend fun listWorkSessionsInRange(ownerId: String, start: Long, end: Long): List<WorkSession> =
            rows.values.filter { it.ownerUserId == ownerId && it.startsAt < end && it.endsAt > start }
    }

    private class FakeFocus : FocusDataSource {
        val sessions = linkedMapOf<String, FocusSession>()
        val segments = linkedMapOf<String, FocusSegment>()
        var failNextSessionCreate = false

        override suspend fun createFocusSession(value: FocusSession): FocusSession {
            if (failNextSessionCreate) {
                failNextSessionCreate = false
                error("创建计时会话失败")
            }
            sessions[value.id] = value
            return value
        }

        override suspend fun findFocusSessionById(id: String): FocusSession? = sessions[id]
        override suspend fun updateFocusSession(value: FocusSession): Boolean {
            if (value.id !in sessions) return false
            sessions[value.id] = value
            return true
        }

        override suspend fun listFocusSessions(ownerId: String, workSessionId: String): List<FocusSession> =
            sessions.values.filter { it.ownerUserId == ownerId && it.workSessionId == workSessionId }

        override suspend fun appendFocusSegment(value: FocusSegment): FocusSegment {
            val existing = segments.values.firstOrNull {
                it.sessionId == value.sessionId && it.clientSegmentId == value.clientSegmentId
            }
            if (existing != null) return existing
            segments[value.id] = value
            return value
        }

        override suspend fun listFocusSegments(sessionId: String): List<FocusSegment> =
            segments.values.filter { it.sessionId == sessionId }

        override suspend fun getFocusDuration(ownerId: String, workSessionId: String): FocusDuration {
            val ids = sessions.values
                .filter { it.ownerUserId == ownerId && it.workSessionId == workSessionId }
                .map { it.id }
                .toSet()
            val matched = segments.values.filter { it.sessionId in ids }
            return FocusDuration(
                foregroundMillis = matched.filter { it.mode == FocusMode.FOREGROUND }.sumOf { it.endsAt - it.startsAt },
                backgroundMillis = matched.filter { it.mode == FocusMode.BACKGROUND }.sumOf { it.endsAt - it.startsAt },
            )
        }
    }

    private class FakeTasks(vararg items: Task) : TaskDataSource {
        private val rows = items.associateBy { it.id }
        override suspend fun listTasks(): List<Task> = rows.values.toList()
        override suspend fun createTask(title: String): Task = error("未使用")
        override suspend fun createTask(value: Task): Task = value
        override suspend fun updateTask(value: Task): Boolean = false
        override suspend fun deleteTaskById(id: String): Boolean = false
        override suspend fun findTaskById(id: String): Task? = rows[id]
        override suspend fun listTasksByWorkspace(workspaceId: String): List<Task> =
            rows.values.filter { it.workspaceId == workspaceId }
    }

    private class FakeWorkspaces(vararg items: Workspace) : WorkspaceDataSource {
        private val rows = items.associateBy { it.id }
        override suspend fun listWorkspaces(status: WorkspaceStatus?): List<Workspace> =
            rows.values.filter { status == null || it.status == status }

        override suspend fun findWorkspaceById(id: String): Workspace? = rows[id]
        override suspend fun listProjectsForCourse(courseId: String, status: WorkspaceStatus?): List<Workspace> = emptyList()
        override suspend fun createWorkspace(kind: WorkspaceKind, name: String, description: String?, linkedCourseId: String?): Workspace =
            error("未使用")

        override suspend fun updateWorkspace(id: String, name: String, description: String?, linkedCourseId: String?): Boolean = false
        override suspend fun archiveWorkspace(id: String): Boolean = false
        override suspend fun restoreWorkspace(id: String): Boolean = false
    }

    private class FakeEvents(vararg items: FixedEvent) : FixedEventDataSource {
        private val rows = items.associateBy { it.id }
        override suspend fun listEvents(): List<FixedEvent> = rows.values.filterNot { it.isCancelled }
        override suspend fun findEventById(id: String): FixedEvent? = rows[id]?.takeUnless { it.isCancelled }
        override suspend fun createEvent(value: FixedEvent): FixedEvent = value
        override suspend fun updateEvent(value: FixedEvent): Boolean = false
        override suspend fun deleteEventById(id: String): Boolean = false
        override suspend fun listEventsByWorkspace(workspaceId: String): List<FixedEvent> = emptyList()
        override suspend fun listEventsInRange(ownerId: String, start: Long, end: Long): List<FixedEvent> =
            listEvents().filter { it.createdBy == ownerId && it.startsAt < end && it.endsAt > start }

        override suspend fun listOverridesIncludingDeleted(): List<FixedEvent> =
            rows.values.filter { it.seriesId != null }

        override suspend fun truncateSeriesUntil(id: String, until: String?): Boolean = false
    }

    @Test
    fun workSessionRepositoryBumpsSharedRevisionOnlyOnActualCommittedWritesAndIsolatesOwnerContext() = runTest {
        val workSessions = FakeWorkSessions()
        val focus = FakeFocus()
        val tracker = LocalDataRevisionTracker()
        val ownSpace = Workspace("w-own", "alice", WorkspaceKind.COURSE, "编译", null, null, WorkspaceStatus.ACTIVE, 0, 0, null)
        val foreignSpace = Workspace("w-bob", "bob", WorkspaceKind.COURSE, "他人", null, null, WorkspaceStatus.ACTIVE, 0, 0, null)
        val ownTask = Task(id = "t-own", title = "我的任务", createdBy = "alice", workspaceId = ownSpace.id)
        val foreignTask = Task(id = "t-bob", title = "Bob任务", createdBy = "bob", workspaceId = foreignSpace.id)
        val ownSeries = FixedEvent(id = "e-own", title = "周三课", startsAt = 100L, endsAt = 200L, createdBy = "alice", repeatWeekdays = setOf(3), repeatUntil = "2026-12-01")
        val ownCancelled = FixedEvent(id = "e-cancel", title = "取消", startsAt = 100L, endsAt = 200L, createdBy = "alice", seriesId = "e-own", originalDate = "2026-11-11", isOverride = true, isCancelled = true)
        val sharedEvent = FixedEvent(id = "e-shared", title = "共享讲座", startsAt = 100L, endsAt = 200L, createdBy = "bob", workspaceId = ownSpace.id)

        val repo = DefaultWorkSessionRepository(
            source = workSessions,
            tasks = FakeTasks(ownTask, foreignTask),
            events = FakeEvents(ownSeries, ownCancelled, sharedEvent),
            workspaces = FakeWorkspaces(ownSpace, foreignSpace),
            ownerUserId = "alice",
            revisionTracker = tracker,
            focus = focus,
        )

        // 空列表与只读事务不递增修订号
        assertTrue(repo.createAll(emptyList()).isEmpty())
        repo.writeTransaction { findTaskById(ownTask.id) }
        assertEquals(0L, repo.revision.value)

        // 上下文读取只返回本人拥有的工作区、可访问任务与本人固定事件/例外
        assertNotNull(repo.findTaskById(ownTask.id))
        assertNull(repo.findTaskById(foreignTask.id))
        assertEquals(listOf("e-own"), repo.listAllFixedEvents().map { it.id })
        assertEquals(listOf("e-cancel"), repo.listFixedEventOverridesIncludingDeleted().map { it.id })

        val s1 = WorkSession("ws-1", ownTask.id, 1_000L, 2_000L, ownerUserId = "alice")
        val s2 = WorkSession("ws-2", ownTask.id, 500L, 900L, ownerUserId = "alice")
        repo.createAll(listOf(s1, s2))
        assertEquals(1L, repo.revision.value)
        assertEquals(listOf("ws-2", "ws-1"), repo.listByTask(ownTask.id).map { it.id })

        // 无变更覆盖或未命中删除不递增修订号
        assertTrue(repo.update(s1))
        assertFalse(repo.deleteById(""))
        assertFalse(repo.deleteById("missing"))
        assertEquals(1L, repo.revision.value)

        // 失败写入不递增修订号
        workSessions.failNextCreate = true
        assertFailsWith<IllegalStateException> {
            repo.createAll(listOf(WorkSession("ws-3", ownTask.id, 3_000L, 4_000L, ownerUserId = "alice")))
        }
        assertEquals(1L, repo.revision.value)

        assertTrue(repo.update(s1.copy(note = "已更新")))
        assertEquals(2L, repo.revision.value)
        assertTrue(repo.deleteById(s2.id))
        assertEquals(3L, repo.revision.value)
    }

    @Test
    fun focusRepositorySharesRevisionSkipsIdempotentSegmentBumpsAndRejectsForeignAccess() = runTest {
        val workSessions = FakeWorkSessions()
        val focus = FakeFocus()
        val tracker = LocalDataRevisionTracker()
        val plan = WorkSession("ws-1", "t-1", 0L, 60_000L, ownerUserId = "alice")
        workSessions.rows[plan.id] = plan

        val repo = DefaultFocusRepository(
            source = focus,
            ownerUserId = "alice",
            revisionTracker = tracker,
            workSessions = workSessions,
        )

        val started = repo.writeTransaction {
            val currentPlan = assertNotNull(findWorkSessionById(plan.id))
            assertTrue(updateWorkSession(currentPlan.copy(focusTargetSeconds = 60L)))
            insertSession(FocusSession("f-1", plan.id, 0L, ownerUserId = "alice"))
        }
        assertEquals(1L, repo.revision.value)
        assertEquals(60L, workSessions.rows[plan.id]?.focusTargetSeconds)

        val seg = FocusSegment("seg-1", started.id, "client-1", 0L, 15_000L, FocusMode.FOREGROUND)
        repo.appendSegment(seg)
        assertEquals(2L, repo.revision.value)

        // 同一 clientSegmentId 幂等重试不重复递增 revision
        assertEquals(seg, repo.appendSegment(seg.copy(id = "seg-retry")))
        assertEquals(2L, repo.revision.value)

        // 无变化的会话保存不递增 revision，真正结束会话才递增
        assertTrue(repo.updateSession(started))
        assertEquals(2L, repo.revision.value)
        assertTrue(repo.updateSession(started.copy(status = FocusSessionStatus.ENDED, endedAt = 15_000L)))
        assertEquals(3L, repo.revision.value)
        assertEquals(FocusDuration(15_000L, 0L), repo.getDuration(plan.id))

        assertFailsWith<IllegalArgumentException> {
            repo.createSession(FocusSession("f-bob", plan.id, 20_000L, ownerUserId = "bob"))
        }
        assertEquals(3L, repo.revision.value)
    }
}
