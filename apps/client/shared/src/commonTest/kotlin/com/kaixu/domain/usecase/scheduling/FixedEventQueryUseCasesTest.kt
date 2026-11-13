package com.kaixu.domain.usecase.scheduling

import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.testing.TestFixedEventRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FixedEventQueryUseCasesTest {
    private val aliceFirst = FixedEvent(
        id = "a", title = "A", startsAt = 1_000, endsAt = 2_000,
        createdBy = "alice", workspaceId = "w1",
    )
    private val aliceSecond = FixedEvent(
        id = "b", title = "B", startsAt = 2_000, endsAt = 3_000,
        createdBy = "alice", workspaceId = "w2",
    )
    private val bobEvent = FixedEvent(
        id = "c", title = "C", startsAt = 1_500, endsAt = 2_500,
        createdBy = "bob",
    )
    private val repository = TestFixedEventRepository(listOf(aliceFirst, aliceSecond, bobEvent))

    @Test
    fun findByIdReturnsEventOrNull() = runTest {
        val find = FindFixedEventByIdUseCase(repository)
        assertEquals(aliceFirst, find("a"))
        assertNull(find("missing"))
        assertFailsWith<IllegalArgumentException> { find(" ") }
    }

    @Test
    fun listByWorkspaceFiltersEvents() = runTest {
        val list = ListFixedEventsByWorkspaceUseCase(repository)
        assertEquals(listOf(aliceFirst), list("w1"))
        assertEquals(listOf(aliceSecond), list("w2"))
        assertFailsWith<IllegalArgumentException> { list(" ") }
    }

    @Test
    fun rangeUsesHalfOpenIntervalsAndOwnerFilter() = runTest {
        val list = ListFixedEventsInRangeUseCase(repository)
        // 窗口 [0, 2000)：A 在窗口内；B 恰好在 2000 开始，不属于该窗口。
        assertEquals(listOf(aliceFirst), list("alice", 0, 2_000))
        // 窗口 [2000, 5000)：A 恰好在 2000 结束，不算重叠；B 属于该窗口。
        assertEquals(listOf(aliceSecond), list("alice", 2_000, 5_000))
    }

    @Test
    fun rejectsInvalidRange() = runTest {
        val list = ListFixedEventsInRangeUseCase(repository)
        assertFailsWith<IllegalArgumentException> { list("alice", 5_000, 5_000) }
        assertFailsWith<IllegalArgumentException> { list("alice", 5_000, 1_000) }
        assertFailsWith<IllegalArgumentException> { list(" ", 0, 1_000) }
    }
}
