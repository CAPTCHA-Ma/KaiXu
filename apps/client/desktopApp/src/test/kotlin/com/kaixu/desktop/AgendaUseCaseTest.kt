package com.kaixu.desktop

import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.database.openDesktopDatabase
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.data.datasource.local.entity.newLocalId
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.workspaces.WorkspaceKind
import com.kaixu.domain.usecase.scheduling.AgendaKind
import com.kaixu.domain.usecase.scheduling.AgendaUseCase
import com.kaixu.domain.usecase.scheduling.AgendaWindow
import com.kaixu.domain.validation.scheduling.ShanghaiTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class AgendaUseCaseTest {
    @Test fun aggregatesOriginalRecordsWithinThreeDaysWithRecurrenceAndPrivacy() = runBlocking {
        val directory = Files.createTempDirectory("kaixu-agenda-data").toFile()
        val database = openDesktopDatabase(directory.resolve("agenda.db"))
        try {
            val sources = LocalDataSources(database)
            val window = AgendaWindow.around("2026-11-14")
            val space = sources.workspaces.createWorkspace(WorkspaceKind.COURSE, "归档课程")
            sources.workspaces.archiveWorkspace(space.id)
            val task = sources.tasks.createTask(Task(newLocalId(), "仅日期截止", workspaceId = space.id, deadlineDate = window.today))
            val outside = sources.tasks.createTask(Task(newLocalId(), "超出范围", deadlineAt = window.endExclusive))
            val undated = sources.tasks.createTask(Task(newLocalId(), "无截止任务"))
            val privateTask = sources.tasks.createTask(Task(newLocalId(), "其他用户", createdBy = newLocalId(), deadlineDate = window.today))
            val session = sources.workSessions.createWorkSession(WorkSession(newLocalId(), task.id, window.start - 60_000, window.start + 60_000))
            sources.workSessions.createWorkSession(session.copy(id = newLocalId(), ownerUserId = newLocalId()))
            sources.workSessions.createWorkSession(session.copy(id = newLocalId(), status = WorkSessionStatus.CANCELLED))
            val start = requireNotNull(ShanghaiTime.parseOrNull("2026-11-13 09:00"))
            val series = sources.fixedEvents.createEvent(FixedEvent(newLocalId(), "重复课程", start, start + 60_000,
                repeatWeekdays = setOf(5, 6, 7), repeatUntil = "2026-11-15"))
            val cancelled = sources.fixedEvents.createEvent(FixedEvent(newLocalId(), "取消课次", start, start + 60_000,
                seriesId = series.id, originalDate = "2026-11-14", isOverride = true))
            sources.fixedEvents.deleteEventById(cancelled.id)
            val result = AgendaUseCase(sources.tasks, sources.fixedEvents, sources.workSessions, sources.workspaces)(LOCAL_ACTOR_ID, window)
            assertEquals(result.entries.map { it.time }.sorted(), result.entries.map { it.time })
            assertFalse(result.entries.any { it.recordId in setOf(outside.id, undated.id, privateTask.id) })
            val deadline = result.entries.single { it.recordId == task.id }
            assertEquals(AgendaKind.TASK_DEADLINE, deadline.kind)
            assertTrue(deadline.dateOnly)
            assertNull(deadline.endsAt)
            assertEquals("归档课程 · 已归档", deadline.workspace)
            assertEquals("2026-11-14", ShanghaiTime.localDateOf(deadline.time))
            assertEquals(session.id, result.entries.single { it.kind == AgendaKind.WORK_SESSION }.recordId)
            assertEquals(2, result.entries.count { it.recordId == series.id })
            assertFalse(result.entries.filter { it.recordId == series.id }.any { ShanghaiTime.localDateOf(it.time) == "2026-11-14" })
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}
