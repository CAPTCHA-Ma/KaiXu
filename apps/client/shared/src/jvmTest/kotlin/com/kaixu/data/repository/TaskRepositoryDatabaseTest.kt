package com.kaixu.data.repository

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.kaixu.app.di.AppGraph
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.RoomFixedEventDataSource
import com.kaixu.data.datasource.local.RoomTaskDataSource
import com.kaixu.data.datasource.local.database.openDesktopDatabase
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.data.datasource.local.entity.WorkSessionEntity
import com.kaixu.domain.model.scheduling.FocusSession
import com.kaixu.domain.model.scheduling.FocusSessionStatus
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.model.tasks.*
import com.kaixu.domain.model.workspaces.WorkspaceKind
import com.kaixu.domain.repository.LocalDataRevisionTracker
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

    @Test fun writeTransactionCommitsAcrossTablesAndRollsBackAtomicallyOnSqliteAbort() = runTest {
        val directory = Files.createTempDirectory("kaixu-task-repo-tx").toFile()
        val file = directory.resolve("tx.db")
        try {
            val db = openDesktopDatabase(file)
            try {
                val sources = LocalDataSources(db)
                val revisionTracker = LocalDataRevisionTracker()
                val repo = DefaultTaskRepository(
                    source = sources.tasks,
                    workspaces = sources.workspaces,
                    workSessions = sources.workSessions,
                    ownerUserId = LOCAL_ACTOR_ID,
                    transaction = sources.transaction,
                    revisionTracker = revisionTracker,
                )

                val ownedSpace = sources.workspaces.createWorkspace(WorkspaceKind.COURSE, "软件工程")
                BundledSQLiteDriver().open(file.absolutePath).use { conn ->
                    conn.execSQL(
                        """INSERT INTO workspaces (id, owner_user_id, kind, visibility, name, status, created_at, updated_at, updated_by, local_revision, sync_state)
                           VALUES ('foreign-ws', 'other-user', 'PROJECT', 'PERSONAL', '他人工作区', 'ACTIVE', 1000, 1000, 'other-user', 0, 'LOCAL_ONLY')""",
                    )
                }
                assertNotNull(repo.findWorkspaceById(ownedSpace.id))
                assertNull(repo.findWorkspaceById("foreign-ws"))
                assertEquals(listOf(ownedSpace.id), repo.listWorkspaces().map { it.id })

                val task = repo.create(Task(id = "task-1", title = "完成任务", workspaceId = ownedSpace.id))
                assertEquals(1L, repo.revision.value)

                val ownedPlan = sources.workSessions.createWorkSession(
                    WorkSession("ws-own", task.id, 10_000L, 20_000L, ownerUserId = LOCAL_ACTOR_ID),
                )
                val foreignPlan = sources.workSessions.createWorkSession(
                    WorkSession("ws-other", task.id, 30_000L, 40_000L, ownerUserId = "other-user"),
                )
                assertEquals(listOf(ownedPlan.id), repo.listOwnerWorkSessionsByTask(task.id).map { it.id })

                sources.focus.createFocusSession(
                    FocusSession("focus-1", ownedPlan.id, 10_000L, ownerUserId = LOCAL_ACTOR_ID),
                )

                // 模拟同一事务中更新工作时段触发 SQLite 异常：任务表更新必须一并回滚，快照和 revision 保持不变
                BundledSQLiteDriver().open(file.absolutePath).use { conn ->
                    conn.execSQL(
                        "CREATE TRIGGER fail_ws_update BEFORE UPDATE ON work_sessions BEGIN SELECT RAISE(ABORT, 'simulated abort'); END",
                    )
                }
                assertFails {
                    repo.writeTransaction {
                        assertTrue(replaceTask(task.copy(status = TaskStatus.COMPLETED, completedAt = 50_000L)))
                        updateOwnerWorkSession(ownedPlan.copy(status = WorkSessionStatus.CANCELLED))
                    }
                }
                assertEquals(TaskStatus.TODO, sources.tasks.findTaskById(task.id)?.status)
                assertEquals(TaskStatus.TODO, repo.tasks.value.single().status)
                assertEquals(WorkSessionStatus.PLANNED, sources.workSessions.findWorkSessionById(ownedPlan.id)?.status)
                assertEquals(1L, repo.revision.value)

                // 移除触发器后重试同一事务：任务完成、本人时段取消且活动计时中断，只递增一次 revision
                BundledSQLiteDriver().open(file.absolutePath).use { it.execSQL("DROP TRIGGER fail_ws_update") }
                repo.writeTransaction {
                    assertTrue(replaceTask(task.copy(status = TaskStatus.COMPLETED, completedAt = 50_000L)))
                    assertTrue(updateOwnerWorkSession(ownedPlan.copy(status = WorkSessionStatus.CANCELLED)))
                }
                assertEquals(TaskStatus.COMPLETED, sources.tasks.findTaskById(task.id)?.status)
                assertEquals(TaskStatus.COMPLETED, repo.tasks.value.single().status)
                assertEquals(WorkSessionStatus.CANCELLED, sources.workSessions.findWorkSessionById(ownedPlan.id)?.status)
                assertEquals(FocusSessionStatus.INTERRUPTED, sources.focus.findFocusSessionById("focus-1")?.status)
                assertEquals(WorkSessionStatus.PLANNED, sources.workSessions.findWorkSessionById(foreignPlan.id)?.status)
                assertEquals(2L, repo.revision.value)
            } finally {
                db.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
