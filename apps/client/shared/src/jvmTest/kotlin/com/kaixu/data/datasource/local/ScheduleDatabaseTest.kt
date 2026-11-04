package com.kaixu.data.datasource.local

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.kaixu.data.datasource.local.database.KaiXuDatabase
import com.kaixu.data.datasource.local.database.openDesktopDatabase
import com.kaixu.data.datasource.local.entity.*
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ScheduleDatabaseTest {
    private suspend fun withDatabase(block: suspend (KaiXuDatabase) -> Unit) {
        val directory = Files.createTempDirectory("kaixu-schedule-test").toFile()
        try {
            val database = openDesktopDatabase(directory.resolve("test.db"))
            try { block(database) } finally { database.close() }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun fullTaskAndEventCrud() = runTest {
        withDatabase { db ->
            val task = TaskEntity(title = "完整任务", description = "说明", deadlineDate = "2026-11-04",
                estimatedMinutes = 60, priority = TaskPriority.HIGH, workspaceId = "workspace-a")
            db.taskDao().insert(task)
            assertEquals(task, db.taskDao().findById(task.id))
            assertEquals(listOf(task), db.taskDao().listByWorkspace("workspace-a"))
            assertEquals(1, db.taskDao().update(task.copy(status = TaskStatus.IN_PROGRESS)))
            val edited = db.taskDao().findById(task.id)!!
            assertEquals(TaskStatus.IN_PROGRESS, edited.status)
            assertEquals(1, edited.localRevision)
            assertEquals(0, db.taskDao().update(task.copy(id = newLocalId())))

            val event = FixedEventEntity(title = "课程", startsAt = 1000, endsAt = 2000,
                workspaceId = "workspace-a", createdBy = "alice")
            val other = FixedEventEntity(title = "他人事件", startsAt = 1000, endsAt = 2000, createdBy = "bob")
            db.fixedEventDao().insert(event)
            db.fixedEventDao().insert(other)
            assertEquals(event, db.fixedEventDao().findById(event.id))
            assertEquals(listOf(event), db.fixedEventDao().listByWorkspace("workspace-a"))
            assertTrue(db.fixedEventDao().listInRange("alice", 2000, 3000).isEmpty())
            assertEquals(listOf(event), db.fixedEventDao().listInRange("alice", 1500, 2500))
            assertEquals(1, db.fixedEventDao().update(event.copy(title = "课程改名")))
            assertEquals("课程改名", db.fixedEventDao().findById(event.id)?.title)
            assertEquals(1, db.fixedEventDao().findById(event.id)?.localRevision)
            assertEquals(1, db.fixedEventDao().deleteById(event.id))
            assertEquals(0, db.fixedEventDao().deleteById(event.id))
            assertNull(db.fixedEventDao().findById(event.id))
            assertEquals(listOf(other), db.fixedEventDao().list())
        }
    }

    @Test
    fun sessionsDetachAtomicallyAndRemainAfterTaskDeletion() = runTest {
        withDatabase { db ->
            val task = TaskEntity(title = "任务")
            db.taskDao().insert(task)
            val session = WorkSessionEntity(taskId = task.id, startsAt = 1000, endsAt = 61000,
                ownerUserId = "alice", note = "本人说明", focusWindowStart = 1000,
                focusWindowEnd = 61000, focusTargetSeconds = 60)
            db.workSessionDao().insert(session)
            val other = session.copy(id = newLocalId(), ownerUserId = "bob")
            db.workSessionDao().insert(other)
            assertEquals(2, db.workSessionDao().listByTask(task.id).size)
            assertEquals(listOf(session), db.workSessionDao().listInRange("alice", 1000, 61000))
            assertTrue(db.workSessionDao().listInRange("alice", 61000, 62000).isEmpty())
            assertEquals(1, db.workSessionDao().update(session.copy(note = "修改说明")))
            assertEquals(1, db.taskDao().deleteById(task.id))
            assertNull(db.taskDao().findById(task.id))
            val detached = db.workSessionDao().findById(session.id)!!
            assertNull(detached.taskId)
            assertNull(detached.stageId)
            assertNotNull(detached.detachedAt)
            assertEquals("TASK_DELETED", detached.detachReason)
            assertEquals("修改说明", detached.note)
            assertEquals(1000, detached.startsAt)
            assertEquals(2, detached.localRevision)
            assertNull(db.workSessionDao().findById(other.id)!!.taskId)
            assertEquals(0, db.taskDao().deleteById(task.id))
            assertFailsWith<IllegalArgumentException> {
                db.workSessionDao().insert(session.copy(id = newLocalId()))
            }
            assertFailsWith<IllegalArgumentException> {
                db.workSessionDao().insert(session.copy(id = newLocalId(), taskId = "missing"))
            }
            assertEquals(1, db.workSessionDao().deleteById(session.id))
            assertEquals(0, db.workSessionDao().deleteById(session.id))
            assertNull(db.workSessionDao().findById(session.id))
        }
    }

    @Test
    fun rejectsInvalidValues() {
        assertFailsWith<IllegalArgumentException> { TaskEntity(title = " ") }
        assertFailsWith<IllegalArgumentException> { TaskEntity(title = "x", deadlineDate = "2026-11-01", deadlineAt = 1) }
        assertFailsWith<IllegalArgumentException> { TaskEntity(title = "x", estimatedMinutes = -1) }
        assertFailsWith<IllegalArgumentException> { TaskEntity(title = "x", deadlineDate = "2026-02-29") }
        assertEquals("2028-02-29", TaskEntity(title = "闰年", deadlineDate = "2028-02-29").deadlineDate)
        assertFailsWith<IllegalArgumentException> { TaskEntity(title = "x", status = TaskStatus.COMPLETED) }
        assertFailsWith<IllegalArgumentException> { FixedEventEntity(title = "x", startsAt = 2, endsAt = 1) }
        assertFailsWith<IllegalArgumentException> { WorkSessionEntity(taskId = null, startsAt = 1, endsAt = 2) }
        assertFailsWith<IllegalArgumentException> { WorkSessionEntity(taskId = "task", startsAt = 0, endsAt = 1000, focusTargetSeconds = 1) }
        assertFailsWith<IllegalArgumentException> { WorkSessionEntity(taskId = "task", startsAt = 0, endsAt = 1000,
            focusWindowStart = 0, focusWindowEnd = 1000, focusTargetSeconds = 2) }
    }

    @Test
    fun taskDeletionRollsBackDetachmentWhenWriteFails() = runTest {
        val directory = Files.createTempDirectory("kaixu-rollback-test").toFile()
        val file = directory.resolve("test.db")
        try {
            val db = openDesktopDatabase(file)
            try {
                val task = TaskEntity(title = "不能部分删除")
                db.taskDao().insert(task)
                val session = WorkSessionEntity(taskId = task.id, startsAt = 1, endsAt = 2)
                db.workSessionDao().insert(session)
                // 在脱链之后的任务写入处制造失败，验证整个事务都回滚。
                BundledSQLiteDriver().open(file.absolutePath).use {
                    it.execSQL("""CREATE TRIGGER fail_task_delete BEFORE UPDATE OF deleted_at ON tasks
                        BEGIN SELECT RAISE(ABORT, 'test failure'); END""")
                }
                assertFails { db.taskDao().deleteById(task.id) }
                assertNotNull(db.taskDao().findById(task.id))
                val saved = db.workSessionDao().findById(session.id)!!
                assertEquals(task.id, saved.taskId)
                assertNull(saved.detachedAt)
                assertEquals(0, saved.localRevision)
            } finally { db.close() }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun migratesRealVersionOneFileAndPreservesTasks() = runTest {
        val directory = Files.createTempDirectory("kaixu-migration-test").toFile()
        val file = directory.resolve("v1.db")
        try {
            BundledSQLiteDriver().open(file.absolutePath).use { connection ->
                connection.execSQL("CREATE TABLE tasks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL)")
                connection.execSQL("INSERT INTO tasks (id, title) VALUES (1, '旧任务一'), (8, '旧任务二')")
                connection.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                connection.execSQL("INSERT INTO room_master_table VALUES (42, '37aa7486b1f39897fbaed089e4d171d6')")
                connection.execSQL("PRAGMA user_version = 1")
            }
            val db = openDesktopDatabase(file)
            try {
                val tasks = db.taskDao().list()
                assertEquals(listOf("旧任务二", "旧任务一"), tasks.map { it.title })
                assertTrue(tasks.all { it.id.matches(Regex("[0-9a-f-]{36}")) })
                assertEquals(2, tasks.map { it.id }.distinct().size)
                assertTrue(tasks.all { it.status == TaskStatus.TODO && it.description == null })
                db.fixedEventDao().insert(FixedEventEntity(title = "新事件", startsAt = 1, endsAt = 2))
                db.workSessionDao().insert(WorkSessionEntity(taskId = tasks.first().id, startsAt = 1, endsAt = 2))
            } finally { db.close() }
            val reopened = openDesktopDatabase(file)
            try {
                assertEquals(2, reopened.taskDao().list().size)
                assertEquals(1, reopened.fixedEventDao().list().size)
                assertEquals(1, reopened.workSessionDao().list().size)
            } finally { reopened.close() }
        } finally { directory.deleteRecursively() }
    }
}
