package com.kaixu.domain.usecase.scheduling

import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.testing.TestFixedEventRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ObserveAndRefreshFixedEventsUseCaseTest {
    private val event = FixedEvent(id = "a", title = "A", startsAt = 1_000, endsAt = 2_000)

    @Test
    fun observeReturnsRepositorySnapshot() = runTest {
        val repository = TestFixedEventRepository(listOf(event))
        assertEquals(listOf(event), ObserveFixedEventsUseCase(repository)().value)
    }

    @Test
    fun refreshReplacesSnapshotWithLatestData() = runTest {
        val repository = TestFixedEventRepository()
        repository.refreshResult = listOf(event)

        RefreshFixedEventsUseCase(repository)()

        assertEquals(listOf(event), repository.events.value)
        assertEquals(1, repository.refreshCalls)
    }
}
