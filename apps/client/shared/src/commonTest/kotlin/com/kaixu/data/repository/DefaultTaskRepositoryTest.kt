package com.kaixu.data.repository

import com.kaixu.data.datasource.TaskDataSource
import com.kaixu.domain.model.tasks.*
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
}
