package com.kaixu.data.repository

import com.kaixu.app.di.AppGraph
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
                    val graph = AppGraph(RoomTaskDataSource(db.taskDao()))
                    assertEquals(original.copy(title = "original"), graph.createTask(original))
                    createdAt = db.taskDao().findById(original.id)!!.createdAt
                    db.workSessionDao().insert(WorkSessionEntity(id = "session", taskId = original.id,
                        stageId = "stage", startsAt = 1000, endsAt = 2000))
                    assertFails { graph.createTask(original) }
                    assertEquals(1, graph.observeTasks().value.size)
                    assertTrue(graph.updateTask(edited))
                    val entity = assertNotNull(db.taskDao().findById(original.id))
                    assertEquals(createdAt, entity.createdAt)
                    assertEquals(1L, entity.localRevision)
                    assertTrue(graph.listTasksByWorkspace("workspace").isEmpty())
                    assertEquals(listOf(edited), graph.listTasksByWorkspace("other"))
                } finally { db.close() }
            }
            openDesktopDatabase(file).let { db ->
                try {
                    val graph = AppGraph(RoomTaskDataSource(db.taskDao()))
                    assertEquals(edited, graph.findTaskById(original.id))
                    graph.refreshTasks()
                    assertEquals(listOf(edited), graph.observeTasks().value)
                    assertEquals(listOf(edited), graph.searchTasks("CHANGED"))
                    val cleared = Task(id = original.id, title = "cleared", createdBy = "carol")
                    assertTrue(graph.updateTask(cleared))
                    assertEquals(cleared, graph.findTaskById(original.id))
                    assertEquals(createdAt, db.taskDao().findById(original.id)!!.createdAt)
                    assertTrue(graph.deleteTask(original.id))
                    assertTrue(graph.observeTasks().value.isEmpty())
                    val session = assertNotNull(db.workSessionDao().findById("session"))
                    assertNull(session.taskId)
                    assertNull(session.stageId)
                    assertEquals("TASK_DELETED", session.detachReason)
                } finally { db.close() }
            }
            openDesktopDatabase(file).let { db ->
                try {
                    val graph = AppGraph(RoomTaskDataSource(db.taskDao()))
                    assertNull(graph.findTaskById(original.id))
                    assertTrue(graph.searchTasks("").isEmpty())
                    assertFalse(graph.updateTask(edited))
                    assertFalse(graph.deleteTask(original.id))
                } finally { db.close() }
            }
        } finally { directory.deleteRecursively() }
    }
}
