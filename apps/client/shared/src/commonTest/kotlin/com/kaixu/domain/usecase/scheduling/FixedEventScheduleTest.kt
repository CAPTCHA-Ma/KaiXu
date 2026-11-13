package com.kaixu.domain.usecase.scheduling

import com.kaixu.data.datasource.FixedEventDataSource
import com.kaixu.data.datasource.WorkSessionDataSource
import com.kaixu.data.datasource.WorkspaceDataSource
import com.kaixu.domain.model.scheduling.EditScope
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.FixedEventDraft
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceKind
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.repository.SchedulingTransaction
import com.kaixu.domain.validation.scheduling.ShanghaiTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class FixedEventScheduleTest {
    private val owner = "owner"
    private val events = FakeEvents()
    private val sessions = FakeSessions()
    private val workspaces = FakeWorkspaces()
    private val transaction = object : SchedulingTransaction {
        override suspend fun <T> run(block: suspend () -> T): T = block()
    }
    private val schedule = FixedEventSchedule(events, sessions, workspaces, owner, transaction)

    private fun millis(text: String) = ShanghaiTime.parseOrNull(text)!!

    private fun draft(
        title: String = "课程",
        start: String = "2026-11-02 09:00",
        end: String = "2026-11-02 10:00",
        workspaceId: String? = null,
        weekdays: Set<Int>? = null,
        until: String? = null,
        reminder: Int? = null,
    ) = FixedEventDraft(
        title = title,
        startsAt = millis(start),
        endsAt = millis(end),
        workspaceId = workspaceId,
        repeatWeekdays = weekdays,
        repeatUntil = until,
        reminderOffsetMinutes = reminder,
    )

    private suspend fun savedEvent(result: FixedEventSaveResult): FixedEvent =
        assertIs<FixedEventSaveResult.Saved>(result).event

    @Test
    fun createsSingleEventAndKeepsWorkspace() = runTest {
        workspaces.add("w1")
        val saved = savedEvent(schedule.create(draft(workspaceId = "w1", reminder = 15)))
        assertEquals("课程", saved.title)
        assertEquals("w1", saved.workspaceId)
        assertEquals(15, saved.reminderOffsetMinutes)
        assertEquals(owner, saved.createdBy)
        assertEquals(1, events.listEvents().size)
    }

    @Test
    fun rejectsBlankTitleAndInvalidRangeWithoutWriting() = runTest {
        assertFailsWith<IllegalArgumentException> { schedule.create(draft(title = " ")) }
        assertFailsWith<IllegalArgumentException> { schedule.create(draft(start = "2026-11-02 10:00", end = "2026-11-02 09:00")) }
        assertTrue(events.listEvents().isEmpty())
    }

    @Test
    fun rejectsArchivedOrForeignWorkspace() = runTest {
        workspaces.add("archived", status = WorkspaceStatus.ARCHIVED)
        workspaces.add("foreign", owner = "other")
        assertFailsWith<IllegalArgumentException> { schedule.create(draft(workspaceId = "archived")) }
        assertFailsWith<IllegalArgumentException> { schedule.create(draft(workspaceId = "foreign")) }
        assertTrue(events.listEvents().isEmpty())
    }

    @Test
    fun overlappingEventNeedsConfirmationAndAdjacentDoesNot() = runTest {
        savedEvent(schedule.create(draft(title = "已有", start = "2026-11-02 09:00", end = "2026-11-02 10:00")))
        // 相邻首尾相接不算冲突。
        savedEvent(schedule.create(draft(title = "相邻", start = "2026-11-02 10:00", end = "2026-11-02 11:00")))
        assertEquals(2, events.listEvents().size)

        val result = schedule.create(draft(title = "重叠", start = "2026-11-02 09:30", end = "2026-11-02 10:30"))
        val pending = assertIs<FixedEventSaveResult.NeedsConfirmation>(result)
        assertEquals(listOf("已有", "相邻"), pending.confirmation.conflicts.map { it.title })
        assertEquals(2, events.listEvents().size)

        val saved = savedEvent(schedule.create(
            draft(title = "重叠", start = "2026-11-02 09:30", end = "2026-11-02 10:30"),
            pending.confirmation,
        ))
        assertEquals("重叠", saved.title)
        assertEquals(3, events.listEvents().size)
    }

    @Test
    fun conflictWithWorkSessionIsReported() = runTest {
        sessions.add(owner, "2026-11-02 09:00", "2026-11-02 10:00")
        val result = schedule.create(draft())
        val pending = assertIs<FixedEventSaveResult.NeedsConfirmation>(result)
        assertEquals(ScheduleConflictKind.WORK_SESSION, pending.confirmation.conflicts.single().kind)
    }

    @Test
    fun confirmationIsInvalidatedWhenInputChanges() = runTest {
        savedEvent(schedule.create(draft(title = "已有")))
        val first = assertIs<FixedEventSaveResult.NeedsConfirmation>(schedule.create(draft(title = "重叠")))
        // 用旧确认保存不同输入，必须重新要求确认。
        val second = schedule.create(draft(title = "改了标题"), first.confirmation)
        assertIs<FixedEventSaveResult.NeedsConfirmation>(second)
    }

    @Test
    fun recurringEventExpandsAndKeepsReminder() = runTest {
        val saved = savedEvent(schedule.create(draft(weekdays = setOf(1, 3), until = "2026-11-20", reminder = 30)))
        assertEquals(setOf(1, 3), saved.repeatWeekdays)
        assertEquals("2026-11-20", saved.repeatUntil)
        val occurrences = schedule.occurrences(millis("2026-11-01 00:00"), millis("2026-11-08 00:00"))
        assertEquals(
            listOf("2026-11-02 09:00", "2026-11-04 09:00"),
            occurrences.map { ShanghaiTime.format(it.startsAt) },
        )
        assertEquals(30, occurrences.first().reminderOffsetMinutes)
    }

    @Test
    fun updateThisOccurrenceCreatesOverrideOnly() = runTest {
        val series = savedEvent(schedule.create(draft(weekdays = setOf(1, 3), until = "2026-11-20")))
        val result = schedule.update(
            target = series,
            draft = draft(title = "调课", start = "2026-11-04 14:00", end = "2026-11-04 15:00"),
            originalDate = "2026-11-04",
            scope = EditScope.THIS_OCCURRENCE,
        )
        savedEvent(result)
        val occurrences = schedule.occurrences(millis("2026-11-01 00:00"), millis("2026-11-08 00:00"))
        assertEquals(listOf("课程", "调课"), occurrences.map { it.title })
        assertEquals("2026-11-04 14:00", ShanghaiTime.format(occurrences[1].startsAt))
        // 序列行未改动。
        assertEquals("课程", events.findEventById(series.id)?.title)
    }

    @Test
    fun updateThisAndFutureTruncatesAndCreatesNewSeries() = runTest {
        val series = savedEvent(schedule.create(draft(weekdays = setOf(1), until = "2026-11-30")))
        val result = schedule.update(
            target = series,
            draft = draft(title = "新课", start = "2026-11-16 14:00", end = "2026-11-16 15:00", weekdays = setOf(1), until = "2026-11-30"),
            originalDate = "2026-11-16",
            scope = EditScope.THIS_AND_FUTURE,
        )
        savedEvent(result)
        assertEquals("2026-11-15", events.findEventById(series.id)?.repeatUntil)
        val occurrences = schedule.occurrences(millis("2026-11-01 00:00"), millis("2026-12-01 00:00"))
        assertEquals(
            listOf("2026-11-02 09:00", "2026-11-09 09:00", "2026-11-16 14:00", "2026-11-23 14:00", "2026-11-30 14:00"),
            occurrences.map { ShanghaiTime.format(it.startsAt) },
        )
        assertEquals(listOf("课程", "课程", "新课", "新课", "新课"), occurrences.map { it.title })
    }

    @Test
    fun deleteThisOccurrenceHidesOnlyThatOccurrence() = runTest {
        val series = savedEvent(schedule.create(draft(weekdays = setOf(1, 3), until = "2026-11-20")))
        assertTrue(schedule.delete(series, originalDate = "2026-11-04", scope = EditScope.THIS_OCCURRENCE))
        val occurrences = schedule.occurrences(millis("2026-11-01 00:00"), millis("2026-11-08 00:00"))
        assertEquals(listOf("2026-11-02 09:00"), occurrences.map { ShanghaiTime.format(it.startsAt) })
        // 重复删除同一次是幂等的。
        assertFalse(schedule.delete(series, originalDate = "2026-11-04", scope = EditScope.THIS_OCCURRENCE))
    }

    @Test
    fun deleteThisAndFutureTruncatesSeries() = runTest {
        val series = savedEvent(schedule.create(draft(weekdays = setOf(1), until = "2026-11-30")))
        assertTrue(schedule.delete(series, originalDate = "2026-11-16", scope = EditScope.THIS_AND_FUTURE))
        val occurrences = schedule.occurrences(millis("2026-11-01 00:00"), millis("2026-12-01 00:00"))
        assertEquals(listOf("2026-11-02 09:00", "2026-11-09 09:00"), occurrences.map { ShanghaiTime.format(it.startsAt) })
    }

    @Test
    fun deleteWholeSeriesHidesAllOccurrences() = runTest {
        val series = savedEvent(schedule.create(draft(weekdays = setOf(1), until = "2026-11-30")))
        schedule.update(
            target = series,
            draft = draft(title = "调课", start = "2026-11-09 14:00", end = "2026-11-09 15:00"),
            originalDate = "2026-11-09",
            scope = EditScope.THIS_OCCURRENCE,
        )
        assertTrue(schedule.delete(series))
        assertTrue(schedule.occurrences(millis("2026-11-01 00:00"), millis("2026-12-01 00:00")).isEmpty())
    }

    @Test
    fun editingForeignEventIsRejected() = runTest {
        val foreign = FixedEvent(id = "x", title = "他人", startsAt = millis("2026-11-02 09:00"),
            endsAt = millis("2026-11-02 10:00"), createdBy = "other")
        events.rows[foreign.id] = foreign
        assertFailsWith<IllegalArgumentException> {
            schedule.update(foreign, draft(), originalDate = null)
        }
        assertFailsWith<IllegalArgumentException> { schedule.delete(foreign) }
        assertNotNull(events.findEventById(foreign.id))
    }

    @Test
    fun occurrencesOnlyContainOwnerEvents() = runTest {
        savedEvent(schedule.create(draft()))
        events.rows["other"] = FixedEvent(
            id = "other", title = "他人事件", startsAt = millis("2026-11-02 11:00"),
            endsAt = millis("2026-11-02 12:00"), createdBy = "other",
        )
        val occurrences = schedule.occurrences(millis("2026-11-02 00:00"), millis("2026-11-03 00:00"))
        assertEquals(listOf("课程"), occurrences.map { it.title })
    }

    @Test
    fun invalidRecurrenceIsRejected() = runTest {
        assertFailsWith<IllegalArgumentException> { schedule.create(draft(weekdays = setOf(1), until = null)) }
        assertFailsWith<IllegalArgumentException> { schedule.create(draft(weekdays = setOf(1), until = "2026-11-01")) }
        assertFailsWith<IllegalArgumentException> { schedule.create(draft(weekdays = emptySet(), until = "2026-11-20")) }
        assertTrue(events.listEvents().isEmpty())
    }

    private class FakeEvents : FixedEventDataSource {
        val rows = linkedMapOf<String, FixedEvent>()

        override suspend fun listEvents() = rows.values.filter { !it.isCancelled }
        override suspend fun findEventById(id: String) = rows[id]?.takeUnless { it.isCancelled }
        override suspend fun createEvent(value: FixedEvent): FixedEvent {
            rows[value.id] = value
            return value
        }
        override suspend fun updateEvent(value: FixedEvent): Boolean {
            if (rows[value.id] == null) return false
            rows[value.id] = value
            return true
        }
        override suspend fun deleteEventById(id: String): Boolean {
            val row = rows[id] ?: return false
            if (row.isCancelled) return false
            rows[id] = row.copy(isCancelled = true)
            return true
        }
        override suspend fun listEventsByWorkspace(workspaceId: String) =
            rows.values.filter { !it.isCancelled && it.workspaceId == workspaceId }
        override suspend fun listEventsInRange(ownerId: String, start: Long, end: Long) =
            rows.values.filter { !it.isCancelled && it.createdBy == ownerId && it.startsAt < end && it.endsAt > start }
        override suspend fun listOverridesIncludingDeleted() = rows.values.filter { it.seriesId != null }
        override suspend fun truncateSeriesUntil(id: String, until: String?): Boolean {
            val row = rows[id] ?: return false
            rows[id] = row.copy(repeatUntil = until, repeatWeekdays = if (until == null) null else row.repeatWeekdays)
            return true
        }
    }

    private class FakeSessions : WorkSessionDataSource {
        private val rows = mutableListOf<WorkSession>()
        fun add(owner: String, start: String, end: String) {
            rows += WorkSession(
                id = "session-${rows.size}", taskId = null,
                startsAt = ShanghaiTime.parseOrNull(start)!!, endsAt = ShanghaiTime.parseOrNull(end)!!,
                ownerUserId = owner,
            )
        }
        override suspend fun listWorkSessions() = rows.toList()
        override suspend fun findWorkSessionById(id: String) = rows.firstOrNull { it.id == id }
        override suspend fun createWorkSession(value: WorkSession): WorkSession { rows += value; return value }
        override suspend fun createWorkSessions(values: List<WorkSession>): List<WorkSession> { rows += values; return values }
        override suspend fun listWorkSessionsByOwnerAndTask(ownerId: String, taskId: String) =
            rows.filter { it.ownerUserId == ownerId && it.taskId == taskId }
        override suspend fun updateWorkSession(value: WorkSession): Boolean = false
        override suspend fun deleteWorkSessionById(id: String): Boolean = false
        override suspend fun listWorkSessionsByTask(taskId: String) = rows.filter { it.taskId == taskId }
        override suspend fun listWorkSessionsInRange(ownerId: String, start: Long, end: Long) =
            rows.filter { it.ownerUserId == ownerId && it.startsAt < end && it.endsAt > start }
    }

    private class FakeWorkspaces : WorkspaceDataSource {
        private val rows = mutableListOf<Workspace>()
        fun add(id: String, status: WorkspaceStatus = WorkspaceStatus.ACTIVE, owner: String = "owner") {
            rows += Workspace(
                id = id, ownerUserId = owner, kind = WorkspaceKind.COURSE, name = id,
                description = null, linkedCourseId = null, status = status,
                createdAt = 0, updatedAt = 0, archivedAt = null,
            )
        }
        override suspend fun listWorkspaces(status: WorkspaceStatus?) = rows.filter { status == null || it.status == status }
        override suspend fun findWorkspaceById(id: String) = rows.firstOrNull { it.id == id }
        override suspend fun listProjectsForCourse(courseId: String, status: WorkspaceStatus?) = emptyList<Workspace>()
        override suspend fun createWorkspace(kind: WorkspaceKind, name: String, description: String?, linkedCourseId: String?) =
            error("测试直接构造")
        override suspend fun updateWorkspace(id: String, name: String, description: String?, linkedCourseId: String?): Boolean = false
        override suspend fun archiveWorkspace(id: String): Boolean = false
        override suspend fun restoreWorkspace(id: String): Boolean = false
    }
}
