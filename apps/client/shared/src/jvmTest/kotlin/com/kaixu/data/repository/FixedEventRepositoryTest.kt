package com.kaixu.data.repository

import androidx.room.execSQL
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteException
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.RoomFixedEventDataSource
import com.kaixu.data.datasource.local.database.openDesktopDatabase
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.workspaces.WorkspaceKind
import com.kaixu.domain.repository.LocalDataRevisionTracker
import com.kaixu.domain.validation.scheduling.ShanghaiTime
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixedEventRepositoryTest {
    @Test
    fun createUpdateDeleteAndQueriesStayConsistent() = runTest {
        val directory = Files.createTempDirectory("kaixu-fixed-event-repo").toFile()
        val database = openDesktopDatabase(directory.resolve("repo.db"))
        try {
            val repository = DefaultFixedEventRepository(RoomFixedEventDataSource(database.fixedEventDao()))
            repository.refresh()

            val created = repository.create(
                FixedEvent(id = "", title = "课程", startsAt = 1_000, endsAt = 2_000, workspaceId = "w1"),
            )
            assertEquals(listOf(created), repository.events.value)

            assertTrue(repository.update(created.copy(title = "课程（改）", startsAt = 3_000, endsAt = 4_000)))
            val updated = repository.events.value.single()
            assertEquals("课程（改）", updated.title)
            assertEquals(3_000, updated.startsAt)

            assertEquals(created.id, repository.findById(created.id)?.id)
            assertEquals(listOf(created.id), repository.listByWorkspace("w1").map { it.id })
            assertEquals(listOf(created.id), repository.listInRange(created.createdBy, 3_500, 5_000).map { it.id })
            assertTrue(repository.listInRange(created.createdBy, 4_000, 5_000).isEmpty())

            assertTrue(repository.deleteById(created.id))
            assertTrue(repository.events.value.isEmpty())
            assertNull(repository.findById(created.id))
            assertFalse(repository.deleteById(created.id))
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun writeTransactionCommitsOverridesAndTruncationAndRollsBackOnSqliteAbort() = runTest {
        val directory = Files.createTempDirectory("kaixu-fixed-event-tx").toFile()
        val file = directory.resolve("tx.db")
        try {
            val database = openDesktopDatabase(file)
            try {
                val sources = LocalDataSources(database)
                val tracker = LocalDataRevisionTracker()
                val repo = DefaultFixedEventRepository(
                    source = sources.fixedEvents,
                    workspaces = sources.workspaces,
                    workSessions = sources.workSessions,
                    tasks = sources.tasks,
                    ownerUserId = LOCAL_ACTOR_ID,
                    transaction = sources.transaction,
                    revisionTracker = tracker,
                )

                val ownedWorkspace = sources.workspaces.createWorkspace(WorkspaceKind.COURSE, "计算机网络")
                database.useWriterConnection { conn ->
                    conn.execSQL(
                        """INSERT INTO workspaces (id, owner_user_id, kind, visibility, name, status, created_at, updated_at, updated_by, local_revision, sync_state)
                           VALUES ('foreign-ws', 'other-user', 'PROJECT', 'PERSONAL', '他人工作区', 'ACTIVE', 1000, 1000, 'other-user', 0, 'LOCAL_ONLY')""",
                    )
                }
                assertNotNull(repo.findWorkspaceById(ownedWorkspace.id))
                assertNull(repo.findWorkspaceById("foreign-ws"))
                assertEquals(listOf(ownedWorkspace.id), repo.listWorkspaces().map { it.id })

                val task = sources.tasks.createTask(Task(id = "task-1", title = "实验预习", workspaceId = ownedWorkspace.id))
                val sessionStart = ShanghaiTime.parseOrNull("2026-11-02 10:00")!!
                val ownedSession = sources.workSessions.createWorkSession(
                    WorkSession("ws-own", task.id, sessionStart, sessionStart + 3_600_000L, ownerUserId = LOCAL_ACTOR_ID),
                )
                sources.workSessions.createWorkSession(
                    WorkSession("ws-foreign", task.id, sessionStart, sessionStart + 3_600_000L, ownerUserId = "other-user"),
                )
                assertEquals(listOf(ownedSession.id), repo.listOwnerWorkSessionsInRange(sessionStart, sessionStart + 3_600_000L).map { it.id })
                assertEquals(listOf(task), repo.listTasks())
                assertEquals(task, repo.findTaskById(task.id))

                val seriesStart = ShanghaiTime.parseOrNull("2026-11-02 09:00")!!
                val series = repo.create(
                    FixedEvent(
                        id = "series-1",
                        title = "计算机网络",
                        startsAt = seriesStart,
                        endsAt = seriesStart + 3_600_000L,
                        workspaceId = ownedWorkspace.id,
                        repeatWeekdays = setOf(1),
                        repeatUntil = "2026-11-30",
                    ),
                )
                assertEquals(1L, repo.revision.value)

                // 创建并删除例外占位于同一事务中：持久化为已取消例外，但不在 events 快照中出现
                val tombStart = ShanghaiTime.parseOrNull("2026-11-09 09:00")!!
                repo.writeTransaction {
                    val tomb = insertEvent(
                        FixedEvent(
                            id = "override-cancel",
                            title = series.title,
                            startsAt = tombStart,
                            endsAt = tombStart + 3_600_000L,
                            seriesId = series.id,
                            originalDate = "2026-11-09",
                            isOverride = true,
                        ),
                    )
                    assertTrue(removeEventById(tomb.id))
                }
                assertEquals(2L, repo.revision.value)
                assertEquals(listOf(series.id), repo.events.value.map { it.id })
                val cancelled = repo.listOverridesIncludingDeleted().single { it.id == "override-cancel" }
                assertTrue(cancelled.isCancelled)

                // 触发器导致第 2 步写入失败时：第 1 步序列截断也必须一并回滚，快照与 revision 不变
                database.useWriterConnection { conn ->
                    conn.execSQL(
                        "CREATE TRIGGER fail_event_insert BEFORE INSERT ON fixed_events BEGIN SELECT RAISE(ABORT, 'simulated failure'); END",
                    )
                }
                val nextStart = ShanghaiTime.parseOrNull("2026-11-16 14:00")!!
                val rejected = assertFailsWith<SQLiteException> {
                    repo.writeTransaction {
                        assertTrue(truncateSeriesUntil(series.id, "2026-11-15"))
                        insertEvent(
                            FixedEvent(
                                id = "series-2",
                                title = "计算机网络（调时段）",
                                startsAt = nextStart,
                                endsAt = nextStart + 3_600_000L,
                                workspaceId = ownedWorkspace.id,
                                repeatWeekdays = setOf(1),
                                repeatUntil = "2026-11-30",
                            ),
                        )
                    }
                }
                assertContains(rejected.message.orEmpty(), "simulated failure")
                assertEquals("2026-11-30", sources.fixedEvents.findEventById(series.id)?.repeatUntil)
                assertEquals("2026-11-30", repo.events.value.single().repeatUntil)
                assertNull(sources.fixedEvents.findEventById("series-2"))
                assertEquals(2L, repo.revision.value)

                // 移除触发器后重试：截断与新建全部落库，revision 只递增 1 次
                database.useWriterConnection { it.execSQL("DROP TRIGGER fail_event_insert") }
                repo.writeTransaction {
                    assertTrue(truncateSeriesUntil(series.id, "2026-11-15"))
                    insertEvent(
                        FixedEvent(
                            id = "series-2",
                            title = "计算机网络（调时段）",
                            startsAt = nextStart,
                            endsAt = nextStart + 3_600_000L,
                            workspaceId = ownedWorkspace.id,
                            repeatWeekdays = setOf(1),
                            repeatUntil = "2026-11-30",
                        ),
                    )
                }
                assertEquals("2026-11-15", sources.fixedEvents.findEventById(series.id)?.repeatUntil)
                assertEquals(setOf(series.id, "series-2"), repo.events.value.map { it.id }.toSet())
                assertEquals(3L, repo.revision.value)
            } finally {
                database.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
