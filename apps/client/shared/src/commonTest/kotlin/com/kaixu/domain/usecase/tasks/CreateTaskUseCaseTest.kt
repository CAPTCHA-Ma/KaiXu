package com.kaixu.domain.usecase.tasks

import com.kaixu.testing.TestTaskRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CreateTaskUseCaseTest {
    @Test fun validatesEvenWithoutAViewModel() = runTest {
        val repository = TestTaskRepository()
        val createTask = CreateTaskUseCase(repository)
        assertFailsWith<IllegalArgumentException> { createTask("   ") }
        assertFailsWith<IllegalArgumentException> { createTask("字".repeat(121)) }
        assertTrue(repository.tasks.value.isEmpty())
    }

    @Test fun normalizesTitleForEveryCaller() = runTest {
        val repository = TestTaskRepository()
        val createTask = CreateTaskUseCase(repository)
        createTask("  整理课件  ")
        assertEquals("整理课件", repository.tasks.value.single().title)
    }
}
