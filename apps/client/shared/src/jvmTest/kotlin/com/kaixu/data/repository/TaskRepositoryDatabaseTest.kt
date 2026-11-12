package com.kaixu.data.repository

import com.kaixu.app.di.AppGraph
import com.kaixu.data.datasource.local.RoomFixedEventDataSource
import com.kaixu.data.datasource.local.RoomTaskDataSource
import com.kaixu.data.datasource.local.database.openDesktopDatabase
import com.kaixu.data.datasource.local.entity.WorkSessionEntity
import com.kaixu.domain.model.tasks.*
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TaskRepositoryDatabaseTest {
    @Test fun completeTaskLifecyclePersistsAndDeleteDetachesSessions() = runTest {
        val directory = Files.createTempDirectory("kaixu-task-repo").toFile()
        val file = directory.resolve("tasks.db")
        val original = Task(id = "task", title = " original ", description = "description",
            workspaceId = "workspace", createdBy = "alice", assigneeUserId = "bob",
            status = TaskStatus.IN_PROGRESS, priority = TaskPriority.HIGH, deadlineDate = "2028-02-29",
            estimatedMinutes = 60, blockedNote = "blocked", sourceExcerpt = "excerpt", sourceUrl = "url")
        val edited = original.copy(title = "edited", description = "changed", workspaceId = "other",
            createdBy = "carol", assigneeUserId = "dave", status = TaskStatus.COMPLETED,
            priority = TaskPriority.LOW, deadlineDate = null, deadlineAt = 9000, estimatedMinutes = 0,
            blockedNote = "new note", completedAt = 8000, sourceExcerpt = "new excerpt", sourceUrl = "new url")
        var createdAt = 0L
        try {
            openDesktopDatabase(file).let { db ->
                try {
                    val graph = AppGraph(RoomTaskDataSource(db.taskDao()), RoomFixedEventDataSource(db.fixedEventDao()))
                    assertEquals(original.copy(title = "original"), graph.taskUseCases.create(original))
                    createdAt = db.taskDao().findById(original.id)!!.createdAt
                    db.workSessionDao().insert(WorkSessionEntity(id = "session", taskId = original.id,
                        stageId = "stage", startsAt = 1000, endsAt = 2000))
                    assertFails { graph.taskUseCases.create(original) }
                    assertEquals(1, graph.taskUseCases.observe().value.size)
                    assertTrue(graph.taskUseCases.update(edited))
                    val entity = assertNotNull(db.taskDao().findById(original.id))
                    assertEquals(createdAt, entity.createdAt)
                    assertEquals(1L, entity.localRevision)
                    assertTrue(graph.taskUseCases.listByWorkspace("workspace").isEmpty())
                    assertEquals(listOf(edited), graph.taskUseCases.listByWorkspace("other"))
                } finally { db.close() }
            }
            openDesktopDatabase(file).let { db ->
                try {
                    val graph = AppGraph(RoomTaskDataSource(db.taskDao()), RoomFixedEventDataSource(db.fixedEventDao()))
                    assertEquals(edited, graph.taskUseCases.findById(original.id))
                    graph.taskUseCases.refresh()
                    assertEquals(listOf(edited), graph.taskUseCases.observe().value)
                    assertEquals(listOf(edited), graph.taskUseCases.search("CHANGED"))
                    val cleared = Task(id = original.id, title = "cleared", createdBy = "carol")
                    assertTrue(graph.taskUseCases.update(cleared))
                    assertEquals(cleared, graph.taskUseCases.findById(original.id))
                    assertEquals(createdAt, db.taskDao().findById(original.id)!!.createdAt)
                    assertTrue(graph.taskUseCases.deleteById(original.id))
                    assertTrue(graph.taskUseCases.observe().value.isEmpty())
                    val session = assertNotNull(db.workSessionDao().findById("session"))
                    assertNull(session.taskId)
                    assertNull(session.stageId)
                    assertEquals("TASK_DELETED", session.detachReason)
                } finally { db.close() }
            }
            openDesktopDatabase(file).let { db ->
                try {
                    val graph = AppGraph(RoomTaskDataSource(db.taskDao()), RoomFixedEventDataSource(db.fixedEventDao()))
                    assertNull(graph.taskUseCases.findById(original.id))
                    assertTrue(graph.taskUseCases.search("").isEmpty())
                    assertFalse(graph.taskUseCases.update(edited))
                    assertFalse(graph.taskUseCases.deleteById(original.id))
                } finally { db.close() }
            }
        } finally { directory.deleteRecursively() }
    }
}
