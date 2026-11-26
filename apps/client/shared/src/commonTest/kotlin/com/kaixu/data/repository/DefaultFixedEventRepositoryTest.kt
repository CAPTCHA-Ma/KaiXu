package com.kaixu.data.repository

import com.kaixu.data.datasource.FixedEventDataSource
import com.kaixu.data.datasource.TaskDataSource
import com.kaixu.data.datasource.WorkSessionDataSource
import com.kaixu.data.datasource.WorkspaceDataSource
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceKind
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.repository.LocalDataRevisionTracker
import com.kaixu.domain.validation.scheduling.ShanghaiTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultFixedEventRepositoryTest {
    private class FakeFixedEventSource : FixedEventDataSource {
        val rows = linkedMapOf<String, FixedEvent>()
        var failOnNextCreate = false

        override suspend fun listEvents(): List<FixedEvent> =
            rows.values.filter { !it.isCancelled }.sortedWith(compareBy({ it.startsAt }, { it.id }))

        override suspend fun findEventById(id: String): FixedEvent? =
            rows[id]?.takeUnless { it.isCancelled }

        override suspend fun createEvent(value: FixedEvent): FixedEvent {
            if (failOnNextCreate) {
                failOnNextCreate = false
                error("数据源写入失败")
            }
            check(value.id !in rows) { "重复 ID" }
            rows[value.id] = value
            return value
        }

        override suspend fun updateEvent(value: FixedEvent): Boolean {
            val current = rows[value.id] ?: return false
            if (current.isCancelled) return false
            rows[value.id] = value
            return true
        }

        override suspend fun deleteEventById(id: String): Boolean {
            val current = rows[id] ?: return false
            if (current.isCancelled) return false
            rows[id] = current.copy(isCancelled = true)
            return true
        }

        override suspend fun listEventsByWorkspace(workspaceId: String): List<FixedEvent> =
            listEvents().filter { it.workspaceId == workspaceId }

        override suspend fun listEventsInRange(ownerId: String, start: Long, end: Long): List<FixedEvent> =
            listEvents().filter { it.createdBy == ownerId && it.startsAt < end && it.endsAt > start }

        override suspend fun listOverridesIncludingDeleted(): List<FixedEvent> =
            rows.values.filter { it.seriesId != null }

        override suspend fun truncateSeriesUntil(id: String, until: String?): Boolean {
            val current = rows[id] ?: return false
            if (current.isCancelled) return false
            rows[id] = current.copy(
                repeatUntil = until,
                repeatWeekdays = if (until == null) null else current.repeatWeekdays,
            )
            return true
        }
    }

    private class FakeWorkspaces(vararg items: Workspace) : WorkspaceDataSource {
        private val rows = items.associateBy { it.id }.toMutableMap()
        override suspend fun listWorkspaces(status: WorkspaceStatus?) =
            rows.values.filter { status == null || it.status == status }
        override suspend fun findWorkspaceById(id: String) = rows[id]
        override suspend fun listProjectsForCourse(courseId: String, status: WorkspaceStatus?) = emptyList<Workspace>()
        override suspend fun createWorkspace(kind: WorkspaceKind, name: String, description: String?, linkedCourseId: String?) =
            error("未使用")
        override suspend fun updateWorkspace(id: String, name: String, description: String?, linkedCourseId: String?) = false
        override suspend fun archiveWorkspace(id: String) = false
        override suspend fun restoreWorkspace(id: String) = false
    }

    private class FakeWorkSessions(vararg items: WorkSession) : WorkSessionDataSource {
        private val rows = items.toList()
        override suspend fun listWorkSessions() = rows
        override suspend fun findWorkSessionById(id: String) = rows.firstOrNull { it.id == id }
        override suspend fun createWorkSession(value: WorkSession) = value
        override suspend fun createWorkSessions(values: List<WorkSession>) = values
        override suspend fun updateWorkSession(value: WorkSession) = false
        override suspend fun deleteWorkSessionById(id: String) = false
        override suspend fun listWorkSessionsByTask(taskId: String) = rows.filter { it.taskId == taskId }
        override suspend fun listWorkSessionsByOwnerAndTask(ownerId: String, taskId: String) =
            rows.filter { it.ownerUserId == ownerId && it.taskId == taskId }
        override suspend fun listWorkSessionsInRange(ownerId: String, start: Long, end: Long) =
            rows.filter { it.ownerUserId == ownerId && it.startsAt < end && it.endsAt > start }
    }

    private class FakeTasks(vararg items: Task) : TaskDataSource {
        private val rows = items.associateBy { it.id }
        override suspend fun listTasks() = rows.values.toList()
        override suspend fun createTask(title: String) = error("未使用")
        override suspend fun createTask(value: Task) = value
        override suspend fun updateTask(value: Task) = false
        override suspend fun deleteTaskById(id: String) = false
        override suspend fun findTaskById(id: String) = rows[id]
        override suspend fun listTasksByWorkspace(workspaceId: String) = rows.values.filter { it.workspaceId == workspaceId }
    }

    @Test
    fun refreshAndCrudPublishSortedSnapshotsAndBumpRevisionOnlyOnMutation() = runTest {
        val source = FakeFixedEventSource()
        val tracker = LocalDataRevisionTracker()
        val repo = DefaultFixedEventRepository(source = source, ownerUserId = "alice", revisionTracker = tracker)

        val later = repo.create(
            FixedEvent(id = "e-later", title = "晚课", startsAt = 3_000, endsAt = 4_000, createdBy = "alice"),
        )
        val earlier = repo.create(
            FixedEvent(id = "", title = "早课", startsAt = 1_000, endsAt = 2_000, createdBy = "alice"),
        )
        assertTrue(earlier.id.isNotBlank())
        assertEquals(listOf(earlier.id, later.id), repo.events.value.map { it.id })
        assertEquals(2L, repo.revision.value)

        // 快照与持久化一致时 refresh 不应递增 revision；外部写入后 refresh 递增。
        repo.refresh()
        assertEquals(2L, repo.revision.value)
        source.rows["ext"] = FixedEvent(id = "ext", title = "外部新增", startsAt = 2_000, endsAt = 2_500, createdBy = "alice")
        repo.refresh()
        assertEquals(3L, repo.revision.value)
        assertEquals(listOf(earlier.id, "ext", later.id), repo.events.value.map { it.id })

        // 未命中的更新或空白/不存在的删除不应递增 revision
        assertFalse(repo.update(later.copy(id = "")))
        assertFalse(repo.update(later.copy(id = "missing")))
        assertFalse(repo.deleteById(""))
        assertFalse(repo.deleteById("missing"))
        assertEquals(3L, repo.revision.value)

        assertTrue(repo.update(earlier.copy(title = "改期早课", startsAt = 5_000, endsAt = 6_000)))
        assertEquals(4L, repo.revision.value)
        assertEquals(listOf("ext", later.id, earlier.id), repo.events.value.map { it.id })

        assertTrue(repo.deleteById("ext"))
        assertEquals(5L, repo.revision.value)
        assertEquals(listOf(later.id, earlier.id), repo.events.value.map { it.id })
        assertNull(repo.findById("ext"))
    }

    @Test
    fun writeTransactionCommitsMultiStepChangesOnceAndRollsBackSnapshotAndRevisionOnFailure() = runTest {
        val source = FakeFixedEventSource()
        val tracker = LocalDataRevisionTracker()
        val repo = DefaultFixedEventRepository(source = source, ownerUserId = "alice", revisionTracker = tracker)

        val seriesStart = ShanghaiTime.parseOrNull("2026-11-02 09:00")!!
        val series = repo.create(
            FixedEvent(
                id = "series-1",
                title = "数据结构",
                startsAt = seriesStart,
                endsAt = seriesStart + 3_600_000L,
                createdBy = "alice",
                repeatWeekdays = setOf(1),
                repeatUntil = "2026-11-30",
            ),
        )
        assertEquals(1L, repo.revision.value)

        // 只读事务不递增 revision
        val count = repo.writeTransaction {
            listAllEvents().size
        }
        assertEquals(1, count)
        assertEquals(1L, repo.revision.value)

        // 多步写入（截断旧序列 + 生成并取消例外占位 + 插入新序列）仅递增一次 revision
        val tombStart = ShanghaiTime.parseOrNull("2026-11-09 09:00")!!
        val nextStart = ShanghaiTime.parseOrNull("2026-11-16 14:00")!!
        repo.writeTransaction {
            assertTrue(truncateSeriesUntil(series.id, "2026-11-15"))
            val tomb = insertEvent(
                FixedEvent(
                    id = "tomb-1",
                    title = series.title,
                    startsAt = tombStart,
                    endsAt = tombStart + 3_600_000L,
                    createdBy = ownerUserId,
                    seriesId = series.id,
                    originalDate = "2026-11-09",
                    isOverride = true,
                ),
            )
            assertTrue(removeEventById(tomb.id))
            insertEvent(
                FixedEvent(
                    id = "series-2",
                    title = "数据结构（调整后）",
                    startsAt = nextStart,
                    endsAt = nextStart + 3_600_000L,
                    createdBy = ownerUserId,
                    repeatWeekdays = setOf(1),
                    repeatUntil = "2026-11-30",
                ),
            )
        }
        assertEquals(2L, repo.revision.value)
        assertEquals("2026-11-15", repo.events.value.first { it.id == series.id }.repeatUntil)
        assertEquals(setOf(series.id, "series-2"), repo.events.value.map { it.id }.toSet())
        val tombstones = repo.listOverridesIncludingDeleted()
        assertEquals(1, tombstones.size)
        assertTrue(tombstones.single().isCancelled)

        // 中途异常时快照与 revision 都不应更新
        assertFailsWith<IllegalStateException> {
            repo.writeTransaction {
                insertEvent(
                    FixedEvent(
                        id = "temp",
                        title = "半途失败",
                        startsAt = nextStart + 7_200_000L,
                        endsAt = nextStart + 10_800_000L,
                        createdBy = ownerUserId,
                    ),
                )
                error("中途异常")
            }
        }
        assertEquals(2L, repo.revision.value)
        assertEquals(setOf(series.id, "series-2"), repo.events.value.map { it.id }.toSet())
    }

    @Test
    fun contextLookupsIsolateOwnerAndValidateInputs() = runTest {
        val ownedActive = Workspace("w-own", "alice", WorkspaceKind.COURSE, "我的课程", null, null, WorkspaceStatus.ACTIVE, 0, 0, null)
        val ownedArchived = Workspace("w-arch", "alice", WorkspaceKind.PROJECT, "已归档", null, null, WorkspaceStatus.ARCHIVED, 0, 0, 1)
        val foreign = Workspace("w-bob", "bob", WorkspaceKind.PROJECT, "他人空间", null, null, WorkspaceStatus.ACTIVE, 0, 0, null)

        val ownPlan = WorkSession("ws-1", "t-1", 10_000, 20_000, ownerUserId = "alice")
        val ownLater = WorkSession("ws-2", "t-1", 15_000, 25_000, ownerUserId = "alice")
        val foreignPlan = WorkSession("ws-bob", "t-1", 10_000, 20_000, ownerUserId = "bob")
        val task = Task(id = "t-1", title = "写实验报告", createdBy = "alice", workspaceId = ownedActive.id)

        val repo = DefaultFixedEventRepository(
            source = FakeFixedEventSource(),
            workspaces = FakeWorkspaces(ownedActive, ownedArchived, foreign),
            workSessions = FakeWorkSessions(ownLater, ownPlan, foreignPlan),
            tasks = FakeTasks(task),
            ownerUserId = "alice",
        )

        assertNotNull(repo.findWorkspaceById(ownedActive.id))
        assertNotNull(repo.findWorkspaceById(ownedArchived.id))
        assertNull(repo.findWorkspaceById(foreign.id))
        assertEquals(setOf(ownedActive.id, ownedArchived.id), repo.listWorkspaces().map { it.id }.toSet())
        assertEquals(listOf(ownedActive.id), repo.listWorkspaces(WorkspaceStatus.ACTIVE).map { it.id })

        assertEquals(listOf(ownPlan.id, ownLater.id), repo.listOwnerWorkSessionsInRange(10_000, 20_000).map { it.id })
        assertEquals(listOf(task), repo.listTasks())
        assertEquals(task, repo.findTaskById(task.id))

        assertFailsWith<IllegalArgumentException> { repo.listOwnerWorkSessionsInRange(20_000, 20_000) }
        assertFailsWith<IllegalArgumentException> { repo.findById(" ") }
        assertFailsWith<IllegalArgumentException> {
            repo.writeTransaction { truncateSeriesUntil("s1", "2026-02-29") }
        }
    }
}
