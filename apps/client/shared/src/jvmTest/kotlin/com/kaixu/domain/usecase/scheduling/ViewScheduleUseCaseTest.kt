package com.kaixu.domain.usecase.scheduling

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.database.openDesktopDatabase
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.ScheduleEntryType
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.tasks.TaskStatus
import com.kaixu.domain.model.workspaces.WorkspaceKind
import com.kaixu.domain.validation.scheduling.ShanghaiTime
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ViewScheduleUseCaseTest {
    private val date = "2026-11-17"
    private val windowStart = ShanghaiTime.atLocalDate(date, 0)
    private val windowEnd = ShanghaiTime.atLocalDate("2026-11-18", 0)
    private fun at(hour: Int, minute: Int = 0) =
        ShanghaiTime.atLocalDate(date, hour * 3_600_000L + minute * 60_000L)

    private suspend fun withStore(block: suspend (LocalDataSources, File) -> Unit) {
        val directory = Files.createTempDirectory("kaixu-view-schedule").toFile()
        try {
            val file = directory.resolve("test.db")
            val db = openDesktopDatabase(file)
            try {
                block(LocalDataSources(db), file)
            } finally {
                db.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun useCase(sources: LocalDataSources) = ViewScheduleUseCase(
        sources.fixedEvents, sources.workSessions, sources.tasks, sources.workspaces, LOCAL_ACTOR_ID, sources.transaction,
    )

    @Test
    fun aggregatesOwnEventsSessionsAccessibleSharedEventsAndSeparatesDeadlines() = runTest {
        withStore { sources, file ->
            val active = sources.workspaces.createWorkspace(WorkspaceKind.PROJECT, "进行中项目")
            val archived = sources.workspaces.createWorkspace(WorkspaceKind.COURSE, "已归档课程")
            sources.workspaces.archiveWorkspace(archived.id)
            val foreignWorkspaceId = "foreign-workspace"
            BundledSQLiteDriver().open(file.absolutePath).use { connection ->
                connection.execSQL(
                    """INSERT INTO workspaces
                    (id, owner_user_id, kind, visibility, name, status, created_at, updated_at, updated_by, local_revision, sync_state)
                    VALUES ('$foreignWorkspaceId', 'bob', 'PROJECT', 'PERSONAL', '外部项目', 'ACTIVE', 0, 0, 'bob', 0, 'LOCAL_ONLY')""",
                )
            }

            val activeTask = sources.tasks.createTask(Task(
                "active-task", "项目截止", workspaceId = active.id, deadlineDate = date,
            ))
            val standaloneTask = sources.tasks.createTask(Task(
                "standalone-task", "独立截止", deadlineAt = at(12),
            ))
            val archivedTask = sources.tasks.createTask(Task(
                "archived-task", "归档截止", workspaceId = archived.id, deadlineDate = date,
            ))
            sources.tasks.createTask(Task(
                "completed", "已完成截止", deadlineDate = date,
                status = TaskStatus.COMPLETED, completedAt = 1,
            ))
            sources.tasks.createTask(Task(
                "assigned", "分配给我的截止", workspaceId = active.id, deadlineDate = date,
                createdBy = "bob", assigneeUserId = LOCAL_ACTOR_ID,
            ))
            sources.tasks.createTask(Task(
                "foreign", "他人截止", workspaceId = active.id, deadlineDate = date, createdBy = "bob",
            ))
            sources.tasks.createTask(Task(
                "outside", "窗口外截止", deadlineDate = "2026-11-19",
            ))

            sources.fixedEvents.createEvent(FixedEvent("own-standalone", "独立事件", at(9), at(10)))
            sources.fixedEvents.createEvent(FixedEvent(
                "own-active", "项目事件", at(10), at(11), workspaceId = active.id,
            ))
            sources.fixedEvents.createEvent(FixedEvent(
                "shared-active", "共享组会", at(11), at(12), workspaceId = active.id, createdBy = "bob",
            ))
            sources.fixedEvents.createEvent(FixedEvent(
                "shared-archived", "归档共享事件", at(12), at(13), workspaceId = archived.id, createdBy = "bob",
            ))
            sources.fixedEvents.createEvent(FixedEvent(
                "inaccessible", "无权共享事件", at(13), at(14), workspaceId = foreignWorkspaceId, createdBy = "bob",
            ))
            sources.fixedEvents.createEvent(FixedEvent(
                "own-in-foreign", "我在外部项目的事件", at(14), at(15), workspaceId = foreignWorkspaceId,
            ))
            sources.fixedEvents.createEvent(FixedEvent(
                "recurring", "每周课", at(20), at(21),
                repeatWeekdays = setOf(2), repeatUntil = "2026-11-24",
            ))

            sources.workSessions.createWorkSession(WorkSession(
                "active-session", activeTask.id, at(15), at(16),
            ))
            sources.workSessions.createWorkSession(WorkSession(
                "standalone-session", standaloneTask.id, at(16), at(17),
            ))
            sources.workSessions.createWorkSession(WorkSession(
                "archived-session", archivedTask.id, at(17), at(18),
            ))
            sources.workSessions.createWorkSession(WorkSession(
                "foreign-session", activeTask.id, at(18), at(19), ownerUserId = "bob",
            ))
            sources.workSessions.createWorkSession(WorkSession(
                "cancelled-session", activeTask.id, at(19), at(20),
                status = WorkSessionStatus.CANCELLED,
            ))

            val view = useCase(sources)(windowStart, windowEnd)

            assertEquals(
                listOf(
                    "own-standalone", "own-active", "shared-active", "shared-archived",
                    "own-in-foreign", "active-session", "standalone-session", "archived-session",
                    "recurring@2026-11-17",
                ),
                view.entries.map { it.key },
            )
            assertEquals(
                mapOf(
                    "own-standalone" to ScheduleEntryType.FIXED_EVENT,
                    "own-active" to ScheduleEntryType.FIXED_EVENT,
                    "shared-active" to ScheduleEntryType.SHARED_EVENT,
                    "shared-archived" to ScheduleEntryType.SHARED_EVENT,
                    "own-in-foreign" to ScheduleEntryType.FIXED_EVENT,
                    "active-session" to ScheduleEntryType.WORK_SESSION,
                    "standalone-session" to ScheduleEntryType.WORK_SESSION,
                    "archived-session" to ScheduleEntryType.WORK_SESSION,
                    "recurring@2026-11-17" to ScheduleEntryType.FIXED_EVENT,
                ),
                view.entries.associate { it.key to it.type },
            )
            assertTrue(view.entries.single { it.key == "shared-archived" }.workspaceArchived)
            assertTrue(view.entries.single { it.key == "archived-session" }.workspaceArchived)
            assertTrue(view.entries.single { it.key == "recurring@2026-11-17" }.isRecurring)
            assertEquals(
                setOf("active-task", "assigned", "archived-task", "standalone-task"),
                view.deadlines.map { it.taskId }.toSet(),
            )
            assertEquals(date, view.deadlines.single { it.taskId == "active-task" }.date)
            assertEquals(at(12), view.deadlines.single { it.taskId == "standalone-task" }.at)
        }
    }

    @Test
    fun workspaceFilterKeepsTheSameWindowAndNarrowsEntriesAndDeadlines() = runTest {
        withStore { sources, _ ->
            val active = sources.workspaces.createWorkspace(WorkspaceKind.PROJECT, "进行中项目")
            val archived = sources.workspaces.createWorkspace(WorkspaceKind.COURSE, "已归档课程")
            sources.workspaces.archiveWorkspace(archived.id)
            sources.tasks.createTask(Task("active-task", "项目截止", workspaceId = active.id, deadlineDate = date))
            sources.tasks.createTask(Task("standalone-task", "独立截止", deadlineDate = date))
            sources.tasks.createTask(Task("archived-task", "归档截止", workspaceId = archived.id, deadlineDate = date))
            sources.fixedEvents.createEvent(FixedEvent("standalone", "独立事件", at(9), at(10)))
            sources.fixedEvents.createEvent(FixedEvent("active-event", "项目事件", at(10), at(11), workspaceId = active.id))
            sources.fixedEvents.createEvent(FixedEvent(
                "archived-event", "归档事件", at(11), at(12), workspaceId = archived.id, createdBy = "bob",
            ))
            sources.workSessions.createWorkSession(WorkSession("session", "active-task", at(12), at(13)))

            val query = useCase(sources)
            val all = query(windowStart, windowEnd, ScheduleWorkspaceFilter.All)
            assertEquals(4, all.entries.size)
            assertEquals(3, all.deadlines.size)

            val activeOnly = query(windowStart, windowEnd, ScheduleWorkspaceFilter.Workspace(active.id))
            assertEquals(listOf("active-event", "session"), activeOnly.entries.map { it.key })
            assertEquals(listOf("active-task"), activeOnly.deadlines.map { it.taskId })

            val standalone = query(windowStart, windowEnd, ScheduleWorkspaceFilter.Standalone)
            assertEquals(listOf("standalone"), standalone.entries.map { it.key })
            assertEquals(listOf("standalone-task"), standalone.deadlines.map { it.taskId })

            val archivedOnly = query(windowStart, windowEnd, ScheduleWorkspaceFilter.Workspace(archived.id))
            assertEquals(listOf("archived-event"), archivedOnly.entries.map { it.key })
            assertEquals(listOf("archived-task"), archivedOnly.deadlines.map { it.taskId })
            assertTrue(archivedOnly.entries.single().workspaceArchived)
        }
    }

    @Test
    fun rejectsAnEmptyWindow() = runTest {
        withStore { sources, _ ->
            assertFailsWith<IllegalArgumentException> { useCase(sources)(windowEnd, windowStart) }
        }
    }
}
