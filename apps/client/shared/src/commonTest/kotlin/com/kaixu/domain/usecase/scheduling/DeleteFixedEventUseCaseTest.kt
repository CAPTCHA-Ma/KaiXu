package com.kaixu.domain.usecase.scheduling

import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.testing.TestFixedEventRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeleteFixedEventUseCaseTest {
    private val existing = FixedEvent(id = "e1", title = "课程", startsAt = 1_000, endsAt = 2_000)
    private val repository = TestFixedEventRepository(listOf(existing))
    private val delete = DeleteFixedEventUseCase(repository)

    @Test
    fun rejectsBlankId() = runTest {
        assertFailsWith<IllegalArgumentException> { delete("   ") }
        assertEquals(0, repository.deleteCalls)
    }

    @Test
    fun removesEventFromSnapshot() = runTest {
        assertTrue(delete("e1"))
        assertTrue(repository.events.value.isEmpty())
    }

    @Test
    fun secondDeleteReturnsFalseWithoutThrowing() = runTest {
        assertTrue(delete("e1"))
        assertFalse(delete("e1"))
        assertEquals(2, repository.deleteCalls)
    }
}
