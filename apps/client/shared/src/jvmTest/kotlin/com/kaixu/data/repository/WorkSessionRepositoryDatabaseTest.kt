package com.kaixu.data.repository

import androidx.room.execSQL
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteException
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.database.openDesktopDatabase
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.domain.model.scheduling.FocusDuration
import com.kaixu.domain.model.scheduling.FocusMode
import com.kaixu.domain.model.scheduling.FocusSegment
import com.kaixu.domain.model.scheduling.FocusSession
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.repository.LocalDataRevisionTracker
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkSessionRepositoryDatabaseTest {
    @Test
    fun writeTransactionsRollBackMultiTableMutationsAndLeaveRevisionUnchangedOnSqliteAbort() = runTest {
        val directory = Files.createTempDirectory("kaixu-work-session-repo").toFile()
        val file = directory.resolve("work-session-repo.db")
        try {
            val db = openDesktopDatabase(file)
            try {
                val sources = LocalDataSources(db)
                val revision = LocalDataRevisionTracker()
                val workSessionRepo = DefaultWorkSessionRepository(
                    source = sources.workSessions,
                    tasks = sources.tasks,
                    events = sources.fixedEvents,
                    workspaces = sources.workspaces,
                    ownerUserId = LOCAL_ACTOR_ID,
                    transaction = sources.transaction,
                    revisionTracker = revision,
                    focus = sources.focus,
                )
                val focusRepo = DefaultFocusRepository(
                    source = sources.focus,
                    ownerUserId = LOCAL_ACTOR_ID,
                    transaction = sources.transaction,
                    revisionTracker = revision,
                    workSessions = sources.workSessions,
                    tasks = sources.tasks,
                    workspaces = sources.workspaces,
                )

                val task = sources.tasks.createTask("算法复习")
                val created = workSessionRepo.createAll(
                    listOf(WorkSession("ws-1", task.id, 0L, 60_000L, ownerUserId = LOCAL_ACTOR_ID)),
                ).single()
                assertEquals(1L, workSessionRepo.revision.value)
                assertEquals(1L, focusRepo.revision.value)

                // 使用 Room 托管的 useWriterConnection 创建触发器，避免 Linux CI 多连接锁冲突
                db.useWriterConnection { conn ->
                    conn.execSQL(
                        "CREATE TRIGGER abort_focus_insert BEFORE INSERT ON focus_sessions BEGIN SELECT RAISE(ABORT, 'simulated focus abort'); END",
                    )
                }

                val aborted = assertFailsWith<SQLiteException> {
                    focusRepo.writeTransaction {
                        assertTrue(updateWorkSession(created.copy(focusTargetSeconds = 60L)))
                        insertSession(FocusSession("focus-1", created.id, 0L, ownerUserId = LOCAL_ACTOR_ID))
                    }
                }
                assertContains(aborted.message.orEmpty(), "simulated focus abort")

                // 懒补写的 focusTargetSeconds 与共享 revision 必须完整回滚
                assertNull(workSessionRepo.findById(created.id)?.focusTargetSeconds)
                assertNull(focusRepo.findSessionById("focus-1"))
                assertEquals(1L, revision.version.value)

                db.useWriterConnection { conn ->
                    conn.execSQL("DROP TRIGGER abort_focus_insert")
                }

                val started = focusRepo.writeTransaction {
                    assertTrue(updateWorkSession(created.copy(focusTargetSeconds = 60L)))
                    insertSession(FocusSession("focus-1", created.id, 0L, ownerUserId = LOCAL_ACTOR_ID))
                }
                assertEquals(2L, revision.version.value)
                assertEquals(60L, workSessionRepo.findById(created.id)?.focusTargetSeconds)

                focusRepo.appendSegment(
                    FocusSegment("seg-1", started.id, "c-1", 0L, 25_000L, FocusMode.FOREGROUND),
                )
                assertEquals(3L, revision.version.value)
                assertEquals(FocusDuration(25_000L, 0L), workSessionRepo.getFocusDuration(created.id))

                // 软删除个人时段仍保留实际计时时长
                assertTrue(workSessionRepo.deleteById(created.id))
                assertEquals(4L, revision.version.value)
                assertNull(workSessionRepo.findById(created.id))
                assertEquals(FocusDuration(25_000L, 0L), workSessionRepo.getFocusDuration(created.id))
            } finally {
                db.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
