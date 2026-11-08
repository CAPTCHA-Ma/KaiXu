package com.kaixu.domain.usecase.scheduling

import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.testing.TestFixedEventRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateFixedEventUseCaseTest {
    private val existing = FixedEvent(id = "e1", title = "课程", startsAt = 1_000, endsAt = 2_000)
    private val repository = TestFixedEventRepository(listOf(existing))
    private val update = UpdateFixedEventUseCase(repository)

    @Test
    fun rejectsMissingId() = runTest {
        assertFailsWith<IllegalArgumentException> { update(existing.copy(id = " ")) }
        assertEquals(0, repository.updateCalls)
    }

    @Test
    fun rejectsInvalidFormWithoutWriting() = runTest {
        assertFailsWith<IllegalArgumentException> { update(existing.copy(endsAt = 1_000)) }
        assertEquals(0, repository.updateCalls)
    }

    @Test
    fun updatesSnapshotOnSuccess() = runTest {
        val changed = existing.copy(title = "  课程（改）  ", startsAt = 5_000, endsAt = 6_000)
        assertTrue(update(changed))
        val saved = repository.events.value.single()
        assertEquals("课程（改）", saved.title)
        assertEquals(5_000, saved.startsAt)
        assertEquals(1, repository.updateCalls)
    }

    @Test
    fun returnsFalseWhenEventMissing() = runTest {
        assertFalse(update(existing.copy(id = "missing")))
    }
}
