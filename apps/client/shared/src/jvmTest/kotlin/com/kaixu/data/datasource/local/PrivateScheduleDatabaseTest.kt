package com.kaixu.data.datasource.local

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.kaixu.data.datasource.local.database.KaiXuDatabase
import com.kaixu.data.datasource.local.database.openDesktopDatabase
import com.kaixu.domain.model.scheduling.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.*

class PrivateScheduleDatabaseTest {
    private suspend fun withFile(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("kaixu-private-schedule").toFile()
        try { block(directory.resolve("test.db")) } finally { directory.deleteRecursively() }
    }

    private suspend fun File.open(block: suspend (KaiXuDatabase, LocalDataSources) -> Unit) {
        val db = openDesktopDatabase(this)
        try { block(db, LocalDataSources(db)) } finally { db.close() }
    }

    @Test
    fun multipleSessionsAndPauseGapsPersistAndCountOutsideThePlanWindow() = runTest {
        withFile { file ->
            file.open { _, sources ->
                val task = sources.tasks.createTask("任务")
                val plan = WorkSession("plan", task.id, 10000, 70000, focusTargetSeconds = 60)
                val other = plan.copy(id = "other")
                assertEquals(listOf(plan, other), sources.workSessions.createWorkSessions(listOf(plan, other)))
                assertEquals(2, sources.workSessions.listWorkSessionsByOwnerAndTask(plan.ownerUserId, task.id).size)
                assertTrue(sources.workSessions.listWorkSessionsByOwnerAndTask("another-owner", task.id).isEmpty())

                val first = FocusSession("f1", plan.id, 0)
                sources.focus.createFocusSession(first)
                val front = FocusSegment("g1", first.id, "submit-1", 0, 20000, FocusMode.FOREGROUND)
                sources.focus.appendFocusSegment(front)
                assertTrue(sources.focus.updateFocusSession(first.copy(status = FocusSessionStatus.PAUSED)))
                assertTrue(sources.focus.updateFocusSession(first))
                // 暂停期间 20000..30000 无片段，继续后记录后台时间。
                sources.focus.appendFocusSegment(FocusSegment("g2", first.id, "submit-2", 30000, 50000, FocusMode.BACKGROUND))
                assertTrue(sources.focus.updateFocusSession(first.copy(status = FocusSessionStatus.ENDED, endedAt = 50000, feedback = "第一轮")))

                val second = FocusSession("f2", plan.id, 80000)
                sources.focus.createFocusSession(second)
                sources.focus.appendFocusSegment(FocusSegment("g3", second.id, "submit-1", 80000, 120000, FocusMode.FOREGROUND))
                sources.focus.updateFocusSession(second.copy(status = FocusSessionStatus.ENDED, endedAt = 120000))
                // 重试可以更换记录 ID，但不能重复累计。
                assertEquals(front, sources.focus.appendFocusSegment(front.copy(id = "retry-id")))
                assertEquals(FocusDuration(60000, 20000), sources.focus.getFocusDuration(plan.ownerUserId, plan.id))
                assertEquals(80000, sources.focus.getFocusDuration(plan.ownerUserId, plan.id).totalMillis)
                assertEquals(FocusDuration(), sources.focus.getFocusDuration("another-owner", plan.id))
                assertEquals(FocusDuration(), sources.focus.getFocusDuration(plan.ownerUserId, other.id))
                assertEquals(WorkSessionStatus.PLANNED, sources.workSessions.findWorkSessionById(plan.id)?.status)
            }
            file.open { _, sources ->
                assertEquals(2, sources.focus.listFocusSessions("00000000-0000-0000-0000-000000000001", "plan").size)
                assertEquals("第一轮", sources.focus.findFocusSessionById("f1")?.feedback)
                assertEquals(2, sources.focus.listFocusSegments("f1").size)
                assertEquals(80000, sources.focus.getFocusDuration("00000000-0000-0000-0000-000000000001", "plan").totalMillis)
            }
        }
    }

    @Test
    fun rejectsInvalidLinksOverlapsAndChangedRetryContent() = runTest {
        withFile { file -> file.open { _, sources ->
            val task = sources.tasks.createTask("任务")
            val plan = WorkSession("plan", task.id, 100, 200, ownerUserId = "alice")
            sources.workSessions.createWorkSession(plan)
            val session = FocusSession("f", plan.id, 0, ownerUserId = "alice")
            assertFails { sources.focus.createFocusSession(session.copy(workSessionId = "missing")) }
            assertFails { sources.focus.createFocusSession(session.copy(ownerUserId = "bob")) }
            sources.focus.createFocusSession(session)
            assertFails { sources.focus.createFocusSession(session.copy(id = "parallel")) }
            sources.focus.updateFocusSession(session.copy(status = FocusSessionStatus.PAUSED))
            assertFails { sources.focus.createFocusSession(session.copy(id = "parallel-paused")) }
            val segment = FocusSegment("g", session.id, "client", 10, 20, FocusMode.FOREGROUND)
            sources.focus.appendFocusSegment(segment)
            assertFails { sources.focus.appendFocusSegment(segment.copy(id = "changed", endsAt = 21)) }
            assertFails { sources.focus.appendFocusSegment(segment.copy(id = "overlap", clientSegmentId = "new", startsAt = 15)) }
            assertFails { sources.focus.appendFocusSegment(segment.copy(id = "orphan", sessionId = "missing")) }
            assertFails { sources.focus.appendFocusSegment(segment.copy(id = "early", clientSegmentId = "early", startsAt = -1)) }
            assertFails { sources.focus.updateFocusSession(session.copy(status = FocusSessionStatus.ENDED, endedAt = 19)) }
            assertFails { sources.focus.updateFocusSession(session.copy(ownerUserId = "bob")) }
            assertFails { sources.focus.updateFocusSession(session.copy(startedAt = 1)) }
            sources.focus.updateFocusSession(session.copy(status = FocusSessionStatus.ENDED, endedAt = 30))
            assertFails { sources.focus.updateFocusSession(session) }
            assertFails { sources.focus.appendFocusSegment(segment.copy(id = "late", clientSegmentId = "late", startsAt = 30, endsAt = 40)) }

            // 不同会话之间也不能把同一段实际时间累计两次；相邻边界允许。
            val second = session.copy(id = "second")
            sources.focus.createFocusSession(second)
            assertFails { sources.focus.appendFocusSegment(segment.copy(id = "cross", sessionId = second.id, clientSegmentId = "cross")) }
            sources.focus.appendFocusSegment(segment.copy(id = "adjacent", sessionId = second.id, startsAt = 20, endsAt = 30))
            assertEquals(FocusDuration(20, 0), sources.focus.getFocusDuration("alice", plan.id))
            assertFalse(sources.focus.updateFocusSession(session.copy(id = "missing")))
        } }
    }

    @Test
    fun batchFailureRollsBackAllPlans() = runTest {
        withFile { file -> file.open { _, sources ->
            val task = sources.tasks.createTask("任务")
            val first = WorkSession("one", task.id, 0, 1000)
            val second = first.copy(id = "two", taskId = "missing")
            assertFails { sources.workSessions.createWorkSessions(listOf(first, second)) }
            assertTrue(sources.workSessions.listWorkSessions().isEmpty())
            assertFails { sources.workSessions.createWorkSessions(listOf(first, first)) }
            assertTrue(sources.workSessions.listWorkSessions().isEmpty())
            assertTrue(sources.workSessions.createWorkSessions(emptyList()).isEmpty())
            assertEquals(2, sources.workSessions.createWorkSessions(listOf(first, first.copy(id = "two"))).size)
        } }
    }

    @Test
    fun deletionStopsActiveTimingButPreservesHistoryAndRollsBackOnFailure() = runTest {
        withFile { file -> file.open { _, sources ->
            val task = sources.tasks.createTask("任务")
            val plan = WorkSession("plan", task.id, 0, 1000)
            sources.workSessions.createWorkSession(plan)
            val focus = FocusSession("focus", plan.id, 0)
            sources.focus.createFocusSession(focus)
            sources.focus.appendFocusSegment(FocusSegment("seg", focus.id, "key", 0, 1000, FocusMode.FOREGROUND))
            BundledSQLiteDriver().open(file.absolutePath).use { connection ->
                connection.execSQL("""CREATE TRIGGER fail_delete BEFORE UPDATE OF deleted_at ON tasks
                    BEGIN SELECT RAISE(ABORT, 'write failure'); END""")
            }
            assertFails { sources.tasks.deleteTaskById(task.id) }
            assertEquals(FocusSessionStatus.ACTIVE, sources.focus.findFocusSessionById(focus.id)?.status)
            assertEquals(task.id, sources.workSessions.findWorkSessionById(plan.id)?.taskId)
            BundledSQLiteDriver().open(file.absolutePath).use { it.execSQL("DROP TRIGGER fail_delete") }
            assertTrue(sources.tasks.deleteTaskById(task.id))
            assertNull(sources.workSessions.findWorkSessionById(plan.id)?.taskId)
            assertEquals(FocusSessionStatus.INTERRUPTED, sources.focus.findFocusSessionById(focus.id)?.status)
            assertTrue(sources.workSessions.deleteWorkSessionById(plan.id))
            assertEquals(1000, sources.focus.getFocusDuration(plan.ownerUserId, plan.id).totalMillis)
            assertEquals(1, sources.focus.listFocusSegments(focus.id).size)
            assertFails { sources.focus.createFocusSession(focus.copy(id = "new")) }
        } }
    }

    @Test
    fun cancellingAndDeletingPlansInterruptSessionsWithoutRemovingFragments() = runTest {
        withFile { file -> file.open { _, sources ->
            val task = sources.tasks.createTask("任务")
            val otherTask = sources.tasks.createTask("另一任务")
            val plan = WorkSession("plan", task.id, 0, 1000)
            sources.workSessions.createWorkSession(plan)
            val focus = FocusSession("focus", plan.id, 0)
            sources.focus.createFocusSession(focus)
            sources.focus.appendFocusSegment(FocusSegment("g", focus.id, "key", 0, 1000, FocusMode.FOREGROUND))
            assertFails { sources.workSessions.updateWorkSession(plan.copy(taskId = otherTask.id)) }
            assertTrue(sources.workSessions.updateWorkSession(plan.copy(status = WorkSessionStatus.CANCELLED)))
            assertEquals(FocusSessionStatus.INTERRUPTED, sources.focus.findFocusSessionById(focus.id)?.status)
            assertEquals(1000, sources.focus.getFocusDuration(plan.ownerUserId, plan.id).totalMillis)
            assertFails { sources.focus.createFocusSession(focus.copy(id = "cancelled")) }

            val next = plan.copy(id = "next")
            sources.workSessions.createWorkSession(next)
            sources.focus.createFocusSession(focus.copy(id = "next-focus", workSessionId = next.id))
            assertTrue(sources.workSessions.deleteWorkSessionById(next.id))
            assertEquals(FocusSessionStatus.INTERRUPTED, sources.focus.findFocusSessionById("next-focus")?.status)
            assertEquals(1, sources.focus.listFocusSegments(focus.id).size)
        } }
    }

    @Test
    fun concurrentStartsAndSegmentRetriesCannotDuplicateActiveSessionsOrDuration() = runTest {
        withFile { file -> file.open { _, sources ->
            val task = sources.tasks.createTask("任务")
            val plan = WorkSession("plan", task.id, 0, 1000)
            sources.workSessions.createWorkSession(plan)
            val started = coroutineScope {
                val gate = CompletableDeferred<Unit>()
                val writes = listOf("first", "second").map { id -> async(Dispatchers.IO) {
                    gate.await()
                    try { sources.focus.createFocusSession(FocusSession(id, plan.id, 0)) }
                    catch (_: IllegalArgumentException) { null }
                } }
                gate.complete(Unit)
                writes.awaitAll().filterNotNull().single()
            }
            val segment = FocusSegment("seg", started.id, "key", 0, 1000, FocusMode.FOREGROUND)
            val saved = coroutineScope {
                val gate = CompletableDeferred<Unit>()
                val writes = listOf("g1", "g2").map { id -> async(Dispatchers.IO) {
                    gate.await()
                    sources.focus.appendFocusSegment(segment.copy(id = id))
                } }
                gate.complete(Unit)
                writes.awaitAll()
            }
            assertEquals(saved[0], saved[1])
            assertEquals(1, sources.focus.listFocusSegments(started.id).size)
            assertEquals(1000, sources.focus.getFocusDuration(plan.ownerUserId, plan.id).totalMillis)
        } }
    }

    @Test
    fun versionFourMigrationPreservesPlansTargetsAndOtherTables() = runTest {
        withFile { file ->
            val schema = File("schemas/com.kaixu.data.datasource.local.database.KaiXuDatabase/4.json").readText()
            BundledSQLiteDriver().open(file.absolutePath).use { connection ->
                var table = ""
                for (line in schema.lines()) {
                    val text = line.trim()
                    if (text.startsWith("\"tableName\":")) table = text.substringAfter(": \"").substringBefore('"')
                    if (text.startsWith("\"createSql\":")) {
                        val sql = text.substringAfter(": \"").substringBeforeLast('"').replace("\${TABLE_NAME}", table)
                        check('\\' !in sql) { "旧 schema SQL 需要显式 JSON 解码" }
                        connection.execSQL(sql)
                    }
                }
                connection.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                connection.execSQL("INSERT INTO room_master_table VALUES (42, '988c442b9dfd894fa121b01af5f33ff0')")
                connection.execSQL("""INSERT INTO tasks
                    (id,title,created_by,status,priority,created_at,updated_at,updated_by,local_revision,sync_state)
                    VALUES ('task','旧任务','owner','TODO','NORMAL',10,11,'owner',1,'LOCAL_ONLY')""")
                connection.execSQL("""INSERT INTO work_sessions
                    (id,task_id,starts_at,ends_at,owner_user_id,status,is_locked,overlap_confirmed,
                    focus_window_start,focus_window_end,focus_target_seconds,created_at,updated_at,updated_by,local_revision,sync_state)
                    VALUES ('plan','task',0,60000,'owner','PLANNED',1,0,0,60000,60,10,11,'owner',2,'LOCAL_ONLY')""")
                connection.execSQL("PRAGMA user_version = 4")
            }
            file.open { db, sources ->
                val plan = assertNotNull(sources.workSessions.findWorkSessionById("plan"))
                assertEquals("task", plan.taskId)
                assertEquals(60, plan.focusTargetSeconds)
                assertTrue(plan.isLocked)
                assertEquals(2L, db.workSessionDao().findById(plan.id)?.localRevision)
                assertEquals("旧任务", sources.tasks.findTaskById("task")?.title)
                assertTrue(sources.resources.listResourcesByWorkspace("none").isEmpty())
                sources.focus.createFocusSession(FocusSession("focus", plan.id, 100000, ownerUserId = "owner"))
                sources.focus.appendFocusSegment(FocusSegment("seg", "focus", "key", 100000, 110000, FocusMode.BACKGROUND))
                assertEquals(FocusDuration(0, 10000), sources.focus.getFocusDuration("owner", plan.id))
            }
            file.open { _, sources -> assertNotNull(sources.focus.findFocusSessionById("focus")) }
        }
    }
}
