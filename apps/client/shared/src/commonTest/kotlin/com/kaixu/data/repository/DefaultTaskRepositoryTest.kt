package com.kaixu.data.repository

import com.kaixu.data.datasource.TaskDataSource
import com.kaixu.data.datasource.WorkSessionDataSource
import com.kaixu.data.datasource.WorkspaceDataSource
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.model.tasks.*
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceKind
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.repository.LocalDataRevisionTracker
import com.kaixu.domain.usecase.tasks.TaskUseCases
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DefaultTaskRepositoryTest {
    private class Source : TaskDataSource {
        val rows = linkedMapOf<String, Task>()
        var failure: Exception? = null
        var calls = 0
        private suspend fun before() {
            calls++
            delay(1)
            failure?.let { throw it }
        }
        override suspend fun listTasks(): List<Task> { before(); return rows.values.toList() }
        override suspend fun createTask(title: String): Task = error("必须传完整 Task")
        override suspend fun createTask(value: Task): Task {
            before()
            check(value.id !in rows) { "重复 ID" }
            rows[value.id] = value
            return value
        }
        override suspend fun updateTask(value: Task): Boolean {
            before()
            if (value.id !in rows) return false
            rows[value.id] = value
            return true
        }
        override suspend fun deleteTaskById(id: String): Boolean { before(); return rows.remove(id) != null }
        override suspend fun findTaskById(id: String): Task? { before(); return rows[id] }
        override suspend fun listTasksByWorkspace(workspaceId: String): List<Task> {
            before(); return rows.values.filter { it.workspaceId == workspaceId }
        }
    }

    private fun fullTask() = Task(id = "task", title = "  Read Kotlin  ", description = "说明",
        workspaceId = "workspace", createdBy = "alice", assigneeUserId = "bob",
        status = TaskStatus.IN_PROGRESS, priority = TaskPriority.HIGH, deadlineDate = "2028-02-29",
        estimatedMinutes = 60, blockedNote = "等待", sourceExcerpt = "原文", sourceUrl = "https://example.com")

    @Test fun fullCrudAndUseCases() = runTest {
        val source = Source()
        val repo = DefaultTaskRepository(source)
        val useCases = TaskUseCases(repo)
        val task = useCases.create(fullTask())
        assertEquals(fullTask().copy(title = "Read Kotlin"), task)
        assertEquals(listOf(task), useCases.observe().value)
        // 改变每个业务字段，id 是更新定位键。
        val edited = task.copy(title = " Done ", description = "新说明", workspaceId = "other",
            createdBy = "carol", assigneeUserId = "dave", status = TaskStatus.COMPLETED,
            priority = TaskPriority.LOW, deadlineDate = null, deadlineAt = 5000,
            estimatedMinutes = 0, blockedNote = "新阻塞", completedAt = 4000,
            sourceExcerpt = "新原文", sourceUrl = "https://example.org")
        assertTrue(useCases.update(edited))
        val expected = edited.copy(title = "Done")
        assertEquals(expected, useCases.findById(task.id))
        assertEquals(listOf(expected), repo.tasks.value)
        assertTrue(useCases.listByWorkspace("workspace").isEmpty())
        assertEquals(listOf(expected), useCases.listByWorkspace("other"))
        assertEquals(listOf(expected), useCases.search(" DONE "))
        assertEquals(listOf(expected), useCases.search("新说明"))
        assertTrue(useCases.search("missing").isEmpty())
        assertEquals(listOf(expected), useCases.search(" "))
        val cleared = Task(id = task.id, title = "清空", createdBy = "carol")
        assertTrue(useCases.update(cleared))
        useCases.refresh()
        assertEquals(listOf(cleared), repo.tasks.value)
        assertTrue(useCases.deleteById(task.id))
        assertNull(useCases.findById(task.id))
        assertFalse(useCases.deleteById(task.id))
        assertFalse(useCases.update(cleared))
        assertTrue(repo.tasks.value.isEmpty())
    }

    @Test fun invalidInputNeverReachesDataSource() = runTest {
        val source = Source()
        val repo = DefaultTaskRepository(source)
        val useCases = TaskUseCases(repo)
        val base = Task(id = "task", title = "valid")
        val invalid = listOf(
            base.copy(id = " "), base.copy(title = " "), base.copy(title = "字".repeat(121)),
            base.copy(createdBy = " "), base.copy(workspaceId = ""), base.copy(assigneeUserId = ""),
            base.copy(deadlineDate = "2026-02-29"), base.copy(deadlineDate = "2028-2-29"),
            base.copy(deadlineDate = "0000-01-01"), base.copy(deadlineDate = "2028-04-31"),
            base.copy(deadlineDate = "2028-01-01", deadlineAt = 1), base.copy(estimatedMinutes = -1),
            base.copy(status = TaskStatus.COMPLETED), base.copy(completedAt = 1),
        )
        for (task in invalid) {
            assertFailsWith<IllegalArgumentException> { repo.create(task) }
            assertFailsWith<IllegalArgumentException> { repo.update(task) }
            assertFailsWith<IllegalArgumentException> { useCases.create(task) }
            assertFailsWith<IllegalArgumentException> { useCases.update(task) }
        }
        assertFailsWith<IllegalArgumentException> { repo.findById("") }
        assertFailsWith<IllegalArgumentException> { repo.deleteById(" ") }
        assertFailsWith<IllegalArgumentException> { repo.listByWorkspace("") }
        assertFailsWith<IllegalArgumentException> { useCases.findById("") }
        assertFailsWith<IllegalArgumentException> { useCases.deleteById("") }
        assertFailsWith<IllegalArgumentException> { useCases.listByWorkspace("") }
        assertEquals(0, source.calls)
        assertTrue(repo.tasks.value.isEmpty())
    }

    @Test fun failedAndCancelledOperationsKeepLastConfirmedSnapshot() = runTest {
        val source = Source()
        val repo = DefaultTaskRepository(source)
        val task = repo.create(Task(id = "saved", title = "saved"))
        assertFailsWith<IllegalStateException> { repo.create(task) }
        for (failure in listOf(IllegalStateException("storage failed"), CancellationException("cancelled"))) {
            source.failure = failure
            val operations: List<suspend () -> Unit> = listOf(
                { repo.create(Task(title = "new")); Unit },
                { repo.update(task.copy(title = "changed")); Unit },
                { repo.deleteById(task.id); Unit }, { repo.refresh() },
                { repo.findById(task.id); Unit }, { repo.listByWorkspace("workspace"); Unit },
                { repo.search(""); Unit },
            )
            for (operation in operations) {
                val thrown = assertFails { operation() }
                assertSame(failure, thrown)
                assertEquals(listOf(task), repo.tasks.value)
            }
        }
        source.failure = null
        assertTrue(repo.update(task.copy(title = "recovered")))
        assertEquals("recovered", repo.tasks.value.single().title)
    }

    @Test fun queriesReadStorageWithoutReplacingWholeListWithSubset() = runTest {
        val source = Source()
        val repo = DefaultTaskRepository(source)
        val personal = repo.create(Task(id = "personal", title = "personal"))
        val shared = Task(id = "shared", title = "shared", workspaceId = "workspace")
        source.rows[shared.id] = shared
        assertEquals(shared, repo.findById(shared.id))
        assertEquals(listOf(shared), repo.listByWorkspace("workspace"))
        assertEquals(listOf(shared), repo.search("shared"))
        assertEquals(listOf(personal), repo.tasks.value)
        // 未 refresh 的持久化记录也可以编辑并发布。
        assertTrue(repo.update(shared.copy(title = "edited")))
        assertEquals(2, repo.tasks.value.size)
        repo.refresh()
        assertEquals(source.rows.values.toSet(), repo.tasks.value.toSet())
    }

    @Test fun concurrentWritesAndRefreshDoNotLoseConfirmedChanges() = runTest {
        val source = Source()
        val repo = DefaultTaskRepository(source)
        (1..20).map { index -> async {
            repo.create(Task(id = "task-$index", title = "Task $index"))
            repo.refresh()
        } }.awaitAll()
        assertEquals(20, repo.tasks.value.size)
        assertEquals(source.rows.values.toSet(), repo.tasks.value.toSet())
    }

    @Test fun revisionTracksConfirmedWritesAndChangedRefreshesOnly() = runTest {
        val source = Source()
        val revision = LocalDataRevisionTracker()
        val repo = DefaultTaskRepository(source, revisionTracker = revision)
        assertEquals(0L, repo.revision.value)

        val task = repo.create(Task(id = "t1", title = "任务一"))
        assertEquals(1L, repo.revision.value)

        assertTrue(repo.update(task.copy(title = "任务一改")))
        assertEquals(2L, repo.revision.value)

        // 数据源与快照一致时 refresh 不递增修订号；外部直接变更后 refresh 才递增
        repo.refresh()
        assertEquals(2L, repo.revision.value)
        source.rows["t2"] = Task(id = "t2", title = "外部新增")
        assertEquals(2, repo.listAll().size)
        assertEquals(1, repo.tasks.value.size)
        assertEquals(2L, repo.revision.value)
        repo.refresh()
        assertEquals(3L, repo.revision.value)

        assertTrue(repo.deleteById("t1"))
        assertEquals(4L, repo.revision.value)

        // 未命中或只读事务不递增修订号
        assertFalse(repo.deleteById("missing"))
        assertFalse(repo.update(Task(id = "missing", title = "不存在")))
        assertEquals(4L, repo.revision.value)
        val found = repo.writeTransaction { findTaskById("t2") }
        assertEquals("外部新增", found?.title)
        assertEquals(4L, repo.revision.value)

        // 失败或取消的写操作不递增修订号
        for (failure in listOf(IllegalStateException("db down"), CancellationException("cancelled"))) {
            source.failure = failure
            assertFails { repo.create(Task(id = "t3", title = "失败")) }
            assertEquals(4L, repo.revision.value)
        }
    }

    @Test fun writeTransactionCommitsMultiRecordChangesWithSingleRevisionOrRollsBackSnapshotOnFailure() = runTest {
        val source = Source()
        val revision = LocalDataRevisionTracker()
        val fakeWorkspaces = object : WorkspaceDataSource {
            val map = linkedMapOf(
                "ws-own" to Workspace("ws-own", "alice", WorkspaceKind.COURSE, "我的课", null, null, WorkspaceStatus.ACTIVE, 1L, 1L, null),
                "ws-other" to Workspace("ws-other", "bob", WorkspaceKind.PROJECT, "他人项目", null, null, WorkspaceStatus.ACTIVE, 1L, 1L, null),
            )
            override suspend fun listWorkspaces(status: WorkspaceStatus?): List<Workspace> =
                map.values.filter { status == null || it.status == status }
            override suspend fun findWorkspaceById(id: String): Workspace? = map[id]
            override suspend fun listProjectsForCourse(courseId: String, status: WorkspaceStatus?): List<Workspace> = emptyList()
            override suspend fun createWorkspace(kind: WorkspaceKind, name: String, description: String?, linkedCourseId: String?): Workspace = error("unused")
            override suspend fun updateWorkspace(id: String, name: String, description: String?, linkedCourseId: String?): Boolean = false
            override suspend fun archiveWorkspace(id: String): Boolean = false
            override suspend fun restoreWorkspace(id: String): Boolean = false
        }
        val fakeSessions = object : WorkSessionDataSource {
            val map = linkedMapOf(
                "s-own" to WorkSession("s-own", "t1", 100L, 200L, ownerUserId = "alice"),
                "s-other" to WorkSession("s-other", "t1", 300L, 400L, ownerUserId = "bob"),
            )
            var failUpdate = false
            override suspend fun listWorkSessions(): List<WorkSession> = map.values.toList()
            override suspend fun findWorkSessionById(id: String): WorkSession? = map[id]
            override suspend fun createWorkSession(value: WorkSession): WorkSession = error("unused")
            override suspend fun createWorkSessions(values: List<WorkSession>): List<WorkSession> = error("unused")
            override suspend fun updateWorkSession(value: WorkSession): Boolean {
                if (failUpdate) throw IllegalStateException("session write failed")
                if (value.id !in map) return false
                map[value.id] = value
                return true
            }
            override suspend fun deleteWorkSessionById(id: String): Boolean = false
            override suspend fun listWorkSessionsByTask(taskId: String): List<WorkSession> = map.values.filter { it.taskId == taskId }
            override suspend fun listWorkSessionsByOwnerAndTask(ownerId: String, taskId: String): List<WorkSession> =
                map.values.filter { it.ownerUserId == ownerId && it.taskId == taskId }
            override suspend fun listWorkSessionsInRange(ownerId: String, start: Long, end: Long): List<WorkSession> = emptyList()
        }

        val repo = DefaultTaskRepository(
            source = source,
            workspaces = fakeWorkspaces,
            workSessions = fakeSessions,
            ownerUserId = "alice",
            revisionTracker = revision,
        )
        assertEquals(listOf("ws-own"), repo.listWorkspaces().map { it.id })
        assertNull(repo.findWorkspaceById("ws-other"))
        assertEquals(listOf("s-own"), repo.listOwnerWorkSessions().map { it.id })

        val original = repo.create(Task(id = "t1", title = "准备完成", createdBy = "alice", workspaceId = "ws-own"))
        assertEquals(1L, revision.version.value)

        // 若同一事务后续写入失败，前面暂存的内存快照不能提前发布，revision 也不应递增
        fakeSessions.failUpdate = true
        assertFailsWith<IllegalStateException> {
            repo.writeTransaction {
                assertTrue(replaceTask(original.copy(status = TaskStatus.COMPLETED, completedAt = 500L)))
                updateOwnerWorkSession(fakeSessions.map.getValue("s-own").copy(status = WorkSessionStatus.CANCELLED))
            }
        }
        assertEquals(TaskStatus.TODO, repo.tasks.value.single().status)
        assertEquals(1L, revision.version.value)

        // 恢复后同一事务内修改任务与本人时段，只发布一次 revision
        fakeSessions.failUpdate = false
        repo.writeTransaction {
            assertTrue(replaceTask(original.copy(status = TaskStatus.COMPLETED, completedAt = 500L)))
            assertTrue(updateOwnerWorkSession(fakeSessions.map.getValue("s-own").copy(status = WorkSessionStatus.CANCELLED)))
            assertFailsWith<IllegalArgumentException> {
                updateOwnerWorkSession(fakeSessions.map.getValue("s-other").copy(status = WorkSessionStatus.CANCELLED))
            }
            assertFalse(
                updateOwnerWorkSession(
                    fakeSessions.map.getValue("s-other").copy(ownerUserId = "alice", status = WorkSessionStatus.CANCELLED),
                ),
            )
        }
        assertEquals(TaskStatus.COMPLETED, repo.tasks.value.single().status)
        assertEquals(WorkSessionStatus.CANCELLED, fakeSessions.map.getValue("s-own").status)
        assertEquals(WorkSessionStatus.PLANNED, fakeSessions.map.getValue("s-other").status)
        assertEquals(2L, revision.version.value)
    }
}
