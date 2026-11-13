package com.kaixu.domain.usecase.scheduling

import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.testing.TestFixedEventRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CreateFixedEventUseCaseTest {
    private val repository = TestFixedEventRepository()
    private val create = CreateFixedEventUseCase(repository)

    private fun event(
        title: String = "课程",
        startsAt: Long = 1_000,
        endsAt: Long = 2_000,
    ) = FixedEvent(id = "", title = title, startsAt = startsAt, endsAt = endsAt)

    @Test
    fun rejectsBlankTitleWithoutWriting() = runTest {
        assertFailsWith<IllegalArgumentException> { create(event(title = "   ")) }
        assertEquals(0, repository.createCalls)
    }

    @Test
    fun rejectsEndNotAfterStart() = runTest {
        assertFailsWith<IllegalArgumentException> { create(event(endsAt = 1_000)) }
        assertEquals(0, repository.createCalls)
    }

    @Test
    fun normalizesTitleAndDescription() = runTest {
        val saved = create(event(title = "  课程  ").copy(description = "  说明  "))
        assertEquals("课程", saved.title)
        assertEquals("说明", saved.description)
        assertEquals(1, repository.createCalls)
    }

    @Test
    fun propagatesWriteFailure() = runTest {
        repository.failNextWrite = true
        assertFailsWith<IllegalStateException> { create(event()) }
    }
}
