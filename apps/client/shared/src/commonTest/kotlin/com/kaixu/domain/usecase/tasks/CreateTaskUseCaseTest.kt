package com.kaixu.domain.usecase.tasks

import com.kaixu.data.datasource.memory.InMemoryTaskDataSource
import com.kaixu.data.repository.DefaultTaskRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CreateTaskUseCaseTest {
    @Test fun validatesEvenWithoutAViewModel() = runTest {
        val source = InMemoryTaskDataSource(latencyMillis = 0)
        val createTask = CreateTaskUseCase(DefaultTaskRepository(source))
        assertFailsWith<IllegalArgumentException> { createTask("   ") }
        assertFailsWith<IllegalArgumentException> { createTask("字".repeat(121)) }
        assertTrue(source.listTasks().isEmpty())
    }

    @Test fun normalizesTitleForEveryCaller() = runTest {
        val source = InMemoryTaskDataSource(latencyMillis = 0)
        val createTask = CreateTaskUseCase(DefaultTaskRepository(source))
        createTask("  整理课件  ")
        assertEquals("整理课件", source.listTasks().single().title)
    }
}
