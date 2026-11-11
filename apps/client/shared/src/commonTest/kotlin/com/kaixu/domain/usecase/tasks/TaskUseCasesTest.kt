package com.kaixu.domain.usecase.tasks

import com.kaixu.testing.TestTaskRepository
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.tasks.TaskStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TaskUseCasesTest {
    @Test fun validatesEvenWithoutAViewModel() = runTest {
        val repository = TestTaskRepository()
        val useCases = TaskUseCases(repository)
        assertFailsWith<IllegalArgumentException> { useCases.create(Task(title = "   ")) }
        assertFailsWith<IllegalArgumentException> { useCases.create(Task(title = "字".repeat(121))) }
        assertTrue(repository.tasks.value.isEmpty())
    }

    @Test fun normalizesTitleForEveryCaller() = runTest {
        val repository = TestTaskRepository()
        val useCases = TaskUseCases(repository)
        useCases.create(Task(title = "  整理课件  "))
        assertEquals("整理课件", repository.tasks.value.single().title)
    }

    @Test fun validatesCompleteInputEvenWhenRepositoryDoesNotValidate() = runTest {
        val repository = TestTaskRepository()
        val useCases = TaskUseCases(repository)
        val original = Task(id = "task", title = "valid")
        val invalid = listOf(
            original.copy(id = ""), original.copy(createdBy = ""),
            original.copy(workspaceId = " "), original.copy(assigneeUserId = " "),
            original.copy(estimatedMinutes = -1), original.copy(deadlineDate = "2026-02-29"),
            original.copy(deadlineDate = "2028-02-29", deadlineAt = 1),
            original.copy(status = TaskStatus.COMPLETED), original.copy(completedAt = 1),
        )
        for (task in invalid) {
            assertFailsWith<IllegalArgumentException> { useCases.create(task) }
            assertFailsWith<IllegalArgumentException> { useCases.update(task) }
        }
        assertTrue(repository.tasks.value.isEmpty())
        val task = original.copy(title = " valid ", description = "详细内容", deadlineDate = "2028-02-29")
        assertEquals(task.copy(title = "valid"), useCases.create(task))
    }
}
