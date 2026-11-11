package com.kaixu.data.datasource.local

import com.kaixu.data.datasource.local.database.KaiXuDatabase
import com.kaixu.data.datasource.local.database.openDesktopDatabase
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.tasks.TaskPriority
import com.kaixu.domain.model.tasks.TaskStatus
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LocalDataSourcesTest {
    private class DatabaseFile(private val file: File) {
        suspend fun open(action: suspend (KaiXuDatabase, LocalDataSources) -> Unit) {
            val database = openDesktopDatabase(file)
            try { action(database, LocalDataSources(database)) } finally { database.close() }
        }
    }

    private suspend fun withFile(block: suspend (DatabaseFile) -> Unit) {
        val directory = Files.createTempDirectory("kaixu-sources-test").toFile()
        val file = directory.resolve("nested/local.db")
        try {
            block(DatabaseFile(file))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun crudAndAllBusinessFieldsSurviveReopen() = runTest {
        withFile { store ->
            val task = Task(id = "task", title = "任务", description = "说明", workspaceId = "workspace",
                createdBy = "alice", assigneeUserId = "bob", status = TaskStatus.IN_PROGRESS,
                priority = TaskPriority.HIGH, deadlineDate = "2026-11-04", estimatedMinutes = 60,
                blockedNote = "等待资料", sourceExcerpt = "原文", sourceUrl = "https://example.com")
            val event = FixedEvent(id = "event", title = "课程", startsAt = 1000, endsAt = 61000,
                description = "教室", workspaceId = "workspace", createdBy = "alice",
                reminderOffsetMinutes = 5, seriesId = "series", originalDate = "2026-11-04", isOverride = true)
            val session = WorkSession(id = "session", taskId = task.id, startsAt = 1000, endsAt = 61000,
                ownerUserId = "alice", stageId = "stage", note = "复习", isLocked = true, overlapConfirmed = true,
                focusTargetSeconds = 60)
            val editedTask = task.copy(title = "完成", status = TaskStatus.COMPLETED, completedAt = 62000,
                deadlineDate = null, deadlineAt = 100000)
            val editedEvent = event.copy(title = "改期", startsAt = 2000, endsAt = 62000)
            val editedSession = session.copy(note = "已完成", status = WorkSessionStatus.COMPLETED)
            var createdAt = 0L

            store.open { db, sources ->
                assertTrue(sources.tasks.listTasks().isEmpty())
                assertTrue(sources.fixedEvents.listEvents().isEmpty())
                assertTrue(sources.workSessions.listWorkSessions().isEmpty())
                assertEquals(task, sources.tasks.createTask(task))
                assertEquals(event, sources.fixedEvents.createEvent(event))
                assertEquals(session, sources.workSessions.createWorkSession(session))
                createdAt = db.taskDao().findById(task.id)!!.createdAt
                assertFails { sources.tasks.createTask(task) }
                assertFails { sources.fixedEvents.createEvent(event) }
                assertFails { sources.workSessions.createWorkSession(session) }
            }
            // 新数据库和新数据源实例从相同文件读取，确保没有依赖内存缓存。
            store.open { db, sources ->
                assertEquals(task, sources.tasks.findTaskById(task.id))
                assertEquals(event, sources.fixedEvents.findEventById(event.id))
                assertEquals(session, sources.workSessions.findWorkSessionById(session.id))
                assertTrue(sources.tasks.updateTask(editedTask))
                assertTrue(sources.fixedEvents.updateEvent(editedEvent))
                assertTrue(sources.workSessions.updateWorkSession(editedSession))
                assertEquals(createdAt, db.taskDao().findById(task.id)!!.createdAt)
                assertEquals(1L, db.taskDao().findById(task.id)!!.localRevision)
                assertEquals(1L, db.fixedEventDao().findById(event.id)!!.localRevision)
                assertEquals(1L, db.workSessionDao().findById(session.id)!!.localRevision)
            }
            store.open { _, sources ->
                assertEquals(listOf(editedTask), sources.tasks.listTasks())
                assertEquals(listOf(editedEvent), sources.fixedEvents.listEvents())
                assertEquals(listOf(editedSession), sources.workSessions.listWorkSessions())
                assertTrue(sources.tasks.deleteTaskById(task.id))
                val detached = assertNotNull(sources.workSessions.findWorkSessionById(session.id))
                assertNull(detached.taskId)
                assertNull(detached.stageId)
                assertNotNull(detached.detachedAt)
                assertEquals("TASK_DELETED", detached.detachReason)
                assertEquals(editedSession.note, detached.note)
                assertEquals(editedSession.startsAt, detached.startsAt)
                assertTrue(sources.workSessions.listWorkSessionsByTask(task.id).isEmpty())
                assertFalse(sources.tasks.updateTask(task))
                assertFalse(sources.tasks.deleteTaskById(task.id))
            }
            store.open { _, sources ->
                assertNull(sources.tasks.findTaskById(task.id))
                assertNull(sources.workSessions.findWorkSessionById(session.id)!!.taskId)
                assertTrue(sources.fixedEvents.deleteEventById(event.id))
                assertTrue(sources.workSessions.deleteWorkSessionById(session.id))
                assertFalse(sources.fixedEvents.deleteEventById(event.id))
                assertFalse(sources.workSessions.deleteWorkSessionById(session.id))
                assertFalse(sources.fixedEvents.updateEvent(event))
                assertFalse(sources.workSessions.updateWorkSession(session))
            }
            store.open { _, sources ->
                assertTrue(sources.tasks.listTasks().isEmpty())
                assertTrue(sources.fixedEvents.listEvents().isEmpty())
                assertTrue(sources.workSessions.listWorkSessions().isEmpty())
                assertNull(sources.fixedEvents.findEventById(event.id))
                assertNull(sources.workSessions.findWorkSessionById(session.id))
                assertFalse(sources.tasks.updateTask(task.copy(id = "missing")))
                assertFalse(sources.fixedEvents.deleteEventById("missing"))
                assertFalse(sources.workSessions.deleteWorkSessionById("missing"))
            }
        }
    }

    @Test
    fun filtersValidationAndExistingTitleApi() = runTest {
        withFile { store ->
            store.open { _, sources ->
                val task = sources.tasks.createTask("兼容标题接口")
                assertEquals(task, sources.tasks.findTaskById(task.id))
                val shared = sources.tasks.createTask(Task("shared", "共享任务", workspaceId = "workspace"))
                assertEquals(listOf(shared), sources.tasks.listTasksByWorkspace("workspace"))
                assertTrue(sources.tasks.listTasksByWorkspace("other").isEmpty())
                val event = FixedEvent("event", "课程", 1000, 2000, workspaceId = "workspace", createdBy = "alice")
                val session = WorkSession("session", task.id, 1000, 2000, ownerUserId = "alice")
                sources.fixedEvents.createEvent(event)
                sources.fixedEvents.createEvent(event.copy(id = "bob-event", createdBy = "bob", workspaceId = null))
                sources.workSessions.createWorkSession(session)
                sources.workSessions.createWorkSession(session.copy(id = "bob-session", ownerUserId = "bob"))
                assertEquals(listOf(event), sources.fixedEvents.listEventsByWorkspace("workspace"))
                assertEquals(listOf(event), sources.fixedEvents.listEventsInRange("alice", 1500, 2500))
                assertEquals(listOf(session), sources.workSessions.listWorkSessionsInRange("alice", 1500, 2500))
                assertEquals(2, sources.workSessions.listWorkSessionsByTask(task.id).size)
                assertTrue(sources.fixedEvents.listEventsInRange("alice", 2000, 3000).isEmpty())
                assertTrue(sources.workSessions.listWorkSessionsInRange("alice", 0, 1000).isEmpty())
                assertFailsWith<IllegalArgumentException> { sources.fixedEvents.listEventsInRange("alice", 2, 1) }
                assertFailsWith<IllegalArgumentException> { sources.workSessions.listWorkSessionsInRange("alice", 1, 1) }
                assertTrue(sources.tasks.updateTask(task.copy(workspaceId = "other", createdBy = "bob")))
                assertEquals("other", sources.tasks.findTaskById(task.id)?.workspaceId)
                assertEquals("bob", sources.tasks.findTaskById(task.id)?.createdBy)
                assertFailsWith<IllegalArgumentException> { sources.tasks.createTask(Task("invalid", " ")) }
                assertFailsWith<IllegalArgumentException> { sources.fixedEvents.updateEvent(event.copy(endsAt = 0)) }
                assertFailsWith<IllegalArgumentException> { sources.workSessions.updateWorkSession(session.copy(ownerUserId = "bob")) }
                assertFailsWith<IllegalArgumentException> { sources.workSessions.createWorkSession(session.copy(id = "orphan", taskId = "missing")) }
                assertEquals(event, sources.fixedEvents.findEventById(event.id))
                assertEquals(session, sources.workSessions.findWorkSessionById(session.id))
            }
        }
    }
}
