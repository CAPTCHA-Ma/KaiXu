package com.kaixu.domain.usecase.scheduling

import com.kaixu.data.datasource.FixedEventDataSource
import com.kaixu.data.datasource.WorkSessionDataSource
import com.kaixu.data.datasource.WorkspaceDataSource
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceKind
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class EditLocalFixedEventUseCaseTest {
    @Test
    fun editsOriginalRecordInTransactionWithoutChangingIdentityOrOwnership() = runTest {
        val fixture = EventEditFixture()
        val original = fixture.original
        assertEquals(FixedEventEditResult.Saved, fixture.save(title = "  更新标题  "))
        assertEquals(1, fixture.transactionCount)
        assertEquals(1, fixture.writes)
        assertEquals(original.copy(title = "更新标题", description = "新说明"), fixture.records.getValue(original.id))
    }

    @Test
    fun rejectsInvalidOrUnavailableStateWithoutWriting() = runTest {
        EventEditFixture().apply {
            assertFailsWith<IllegalArgumentException> { save(title = " \t ") }
            assertFailsWith<IllegalArgumentException> { save(endsAt = original.startsAt) }
            records[original.id] = original.copy(title = "另一个入口已修改")
            assertFailsWith<IllegalArgumentException> { save() }
            assertEquals(0, writes)
        }
        EventEditFixture().apply {
            workspace = workspace.copy(status = WorkspaceStatus.ARCHIVED)
            assertFailsWith<IllegalArgumentException> { save() }
            assertEquals(0, writes)
        }
        EventEditFixture().apply {
            records.remove(original.id)
            assertFailsWith<IllegalArgumentException> { save() }
            assertEquals(0, writes)
        }
        EventEditFixture().apply {
            val foreign = original.copy(createdBy = "another-owner")
            records[original.id] = foreign
            assertFailsWith<IllegalArgumentException> { useCase.save(foreign, "改名", null, 1000, 2000) }
            val recurring = original.copy(seriesId = "series", originalDate = "2026-11-08")
            records[original.id] = recurring
            assertFailsWith<IllegalArgumentException> { useCase.save(recurring, "改名", null, 1000, 2000) }
            assertEquals(0, writes)
        }
    }

    @Test
    fun conflictsArePersonalHalfOpenAndRequireFreshConfirmation() = runTest {
        val fixture = EventEditFixture()
        fixture.records["conflict"] = fixture.original.copy(id = "conflict", startsAt = 1800, endsAt = 2400)
        fixture.records["adjacent"] = fixture.original.copy(id = "adjacent", startsAt = 2500, endsAt = 3000)
        fixture.records["foreign"] = fixture.original.copy(id = "foreign", createdBy = "other")
        fixture.sessionRecords += WorkSession("own", "task", 2000, 2400, ownerUserId = "me")
        fixture.sessionRecords += WorkSession("cancelled", "task", 2000, 2400, ownerUserId = "me", status = WorkSessionStatus.CANCELLED)
        fixture.sessionRecords += WorkSession("foreign", "task", 2000, 2400, ownerUserId = "other")
        fixture.sessionRecords += WorkSession("adjacent", "task", 1000, 1500, ownerUserId = "me")
        val first = assertIs<FixedEventEditResult.NeedsConfirmation>(fixture.save(startsAt = 1500, endsAt = 2500))
        assertEquals(listOf("conflict"), first.confirmation.events.map { it.id })
        assertEquals(listOf("own"), first.confirmation.sessions.map { it.id })
        assertEquals(0, fixture.writes)
        // 用户查看提示期间，本人的另一安排发生变化，旧确认不能覆盖新冲突。
        fixture.records["late-conflict"] = fixture.original.copy(id = "late-conflict", startsAt = 2000, endsAt = 2300)
        val second = assertIs<FixedEventEditResult.NeedsConfirmation>(
            fixture.save(startsAt = 1500, endsAt = 2500, confirmed = first.confirmation),
        )
        assertEquals(0, fixture.writes)
        assertEquals(FixedEventEditResult.Saved, fixture.save(startsAt = 1500, endsAt = 2500, confirmed = second.confirmation))
        assertEquals(1, fixture.writes)
        assertEquals(3, fixture.transactionCount)
    }

    @Test
    fun confirmationCannotAuthorizeDifferentInputOrAnArchivedWorkspace() = runTest {
        val fixture = EventEditFixture()
        fixture.records["conflict"] = fixture.original.copy(id = "conflict")
        val first = assertIs<FixedEventEditResult.NeedsConfirmation>(fixture.save())
        val second = assertIs<FixedEventEditResult.NeedsConfirmation>(fixture.save(title = "另一标题", confirmed = first.confirmation))
        fixture.workspace = fixture.workspace.copy(status = WorkspaceStatus.ARCHIVED)
        assertFailsWith<IllegalArgumentException> { fixture.save(title = "另一标题", confirmed = second.confirmation) }
        assertEquals(0, fixture.writes)
    }

    @Test
    fun independentEventKeepsNullWorkspaceAndFailedWriteIsNotSuccess() = runTest {
        val fixture = EventEditFixture()
        val independent = fixture.original.copy(workspaceId = null)
        fixture.records[independent.id] = independent
        fixture.rejectWrite = true
        assertFailsWith<IllegalStateException> { fixture.useCase.save(independent, "更新", null, 1000, 2000) }
        assertEquals(independent, fixture.records.getValue(independent.id))
        fixture.rejectWrite = false
        assertEquals(FixedEventEditResult.Saved, fixture.useCase.save(independent, "更新", null, 1000, 2000))
        assertEquals(null, fixture.records.getValue(independent.id).workspaceId)
    }
}

private class EventEditFixture {
    val original = FixedEvent("event", "原标题", 1000, 2000, "原说明", "space", "me", reminderOffsetMinutes = 5)
    val records = mutableMapOf(original.id to original)
    val sessionRecords = mutableListOf<WorkSession>()
    var workspace = Workspace("space", "me", WorkspaceKind.COURSE, "课程", null, null, WorkspaceStatus.ACTIVE, 0, 0, null)
    var transactionCount = 0
    var transactionOpen = false
    var writes = 0
    var rejectWrite = false
    private fun checkTransaction() = check(transactionOpen) { "所有读取和写入必须位于同一事务" }

    private val events = object : FixedEventDataSource {
        override suspend fun listEvents(): List<FixedEvent> = error("unused")
        override suspend fun findEventById(id: String): FixedEvent? { checkTransaction(); return records[id] }
        override suspend fun createEvent(value: FixedEvent): FixedEvent = error("unused")
        override suspend fun updateEvent(value: FixedEvent): Boolean {
            checkTransaction()
            if (rejectWrite) return false
            writes++
            records[value.id] = value
            return true
        }
        override suspend fun deleteEventById(id: String): Boolean = error("unused")
        override suspend fun listEventsByWorkspace(workspaceId: String): List<FixedEvent> = error("unused")
        override suspend fun listEventsInRange(ownerId: String, start: Long, end: Long): List<FixedEvent> {
            checkTransaction()
            return records.values.toList()
        }
    }
    private val sessions = object : WorkSessionDataSource {
        override suspend fun listWorkSessions(): List<WorkSession> = error("unused")
        override suspend fun findWorkSessionById(id: String): WorkSession? = error("unused")
        override suspend fun createWorkSession(value: WorkSession): WorkSession = error("unused")
        override suspend fun createWorkSessions(values: List<WorkSession>): List<WorkSession> = error("unused")
        override suspend fun listWorkSessionsByOwnerAndTask(ownerId: String, taskId: String): List<WorkSession> = error("unused")
        override suspend fun updateWorkSession(value: WorkSession): Boolean = error("unused")
        override suspend fun deleteWorkSessionById(id: String): Boolean = error("unused")
        override suspend fun listWorkSessionsByTask(taskId: String): List<WorkSession> = error("unused")
        override suspend fun listWorkSessionsInRange(ownerId: String, start: Long, end: Long): List<WorkSession> {
            checkTransaction()
            return sessionRecords.toList()
        }
    }
    private val workspaces = object : WorkspaceDataSource {
        override suspend fun listWorkspaces(status: WorkspaceStatus?): List<Workspace> = error("unused")
        override suspend fun findWorkspaceById(id: String): Workspace? { checkTransaction(); return workspace.takeIf { it.id == id } }
        override suspend fun listProjectsForCourse(courseId: String, status: WorkspaceStatus?): List<Workspace> = error("unused")
        override suspend fun createWorkspace(kind: WorkspaceKind, name: String, description: String?, linkedCourseId: String?): Workspace = error("unused")
        override suspend fun updateWorkspace(id: String, name: String, description: String?, linkedCourseId: String?): Boolean = error("unused")
        override suspend fun archiveWorkspace(id: String): Boolean = error("unused")
        override suspend fun restoreWorkspace(id: String): Boolean = error("unused")
    }
    val useCase = EditLocalFixedEventUseCase(events, sessions, workspaces, "me") { block ->
        check(!transactionOpen)
        transactionOpen = true
        transactionCount++
        try { block() } finally { transactionOpen = false }
    }

    suspend fun save(
        title: String = "新标题",
        startsAt: Long = original.startsAt,
        endsAt: Long = original.endsAt,
        confirmed: FixedEventEditConfirmation? = null,
    ): FixedEventEditResult = useCase.save(original, title, "新说明", startsAt, endsAt, confirmed)
}
