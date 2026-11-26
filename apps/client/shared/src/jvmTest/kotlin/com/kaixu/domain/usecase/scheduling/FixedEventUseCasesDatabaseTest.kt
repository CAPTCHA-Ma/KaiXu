package com.kaixu.domain.usecase.scheduling

import androidx.room.execSQL
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteException
import com.kaixu.core.platform.LocalZone
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.database.openDesktopDatabase
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.data.repository.DefaultFixedEventRepository
import com.kaixu.data.repository.DefaultTaskRepository
import com.kaixu.domain.model.scheduling.CalendarBlockKind
import com.kaixu.domain.model.scheduling.CalendarQuery
import com.kaixu.domain.model.scheduling.CalendarViewMode
import com.kaixu.domain.model.scheduling.CalendarWorkspaceFilter
import com.kaixu.domain.model.scheduling.EditScope
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.FixedEventDraft
import com.kaixu.domain.model.scheduling.FixedEventTarget
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.tasks.TaskDraft
import com.kaixu.domain.model.workspaces.WorkspaceKind
import com.kaixu.domain.repository.LocalDataRevisionTracker
import com.kaixu.domain.usecase.tasks.TaskUseCases
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixedEventUseCasesDatabaseTest {
    private val shanghai = LocalZone.of("Asia/Shanghai")

    private fun at(date: String, hour: Int, minute: Int = 0): Long =
        shanghai.atLocalDateTimeMillis(date, (hour * 60L + minute) * 60_000L)

    @Test
    fun endToEndRoomLifecycleSharedRevisionAndCalendarQuery() = runTest {
        val directory = Files.createTempDirectory("kaixu-fixed-usecases").toFile()
        val file = directory.resolve("fixed-usecases.db")
        try {
            val db = openDesktopDatabase(file)
            try {
                val sources = LocalDataSources(db)
                val sharedRevision = LocalDataRevisionTracker()
                val taskRepo = DefaultTaskRepository(
                    source = sources.tasks,
                    workspaces = sources.workspaces,
                    workSessions = sources.workSessions,
                    ownerUserId = LOCAL_ACTOR_ID,
                    transaction = sources.transaction,
                    revisionTracker = sharedRevision,
                )
                val eventRepo = DefaultFixedEventRepository(
                    source = sources.fixedEvents,
                    workspaces = sources.workspaces,
                    workSessions = sources.workSessions,
                    tasks = sources.tasks,
                    ownerUserId = LOCAL_ACTOR_ID,
                    transaction = sources.transaction,
                    revisionTracker = sharedRevision,
                )
                val taskUseCases = TaskUseCases(taskRepo, defaultZone = shanghai)
                val eventUseCases = FixedEventUseCases(eventRepo, defaultZone = shanghai)

                val workspace = sources.workspaces.createWorkspace(WorkspaceKind.COURSE, "编译原理")
                val task = taskUseCases.createFromDraft(
                    TaskDraft(title = "词法分析实验", workspaceId = workspace.id),
                )
                // 任务写入应经由同一 LocalDataRevision 通知固定事件模块
                assertEquals(1L, eventUseCases.revision.value)

                sources.workSessions.createWorkSession(
                    WorkSession("ws-1", task.id, at("2026-11-02", 9), at("2026-11-02", 10), ownerUserId = LOCAL_ACTOR_ID),
                )

                val createDraft = FixedEventDraft(
                    title = "编译原理课程",
                    startsAt = at("2026-11-02", 9, 30),
                    endsAt = at("2026-11-02", 11, 30),
                    workspaceId = workspace.id,
                    repeatWeekdays = setOf(1, 3),
                    repeatUntil = "2026-11-23",
                    reminderOffsetMinutes = 15,
                )
                val pending = assertIs<FixedEventSaveResult.NeedsConfirmation>(eventUseCases.create(createDraft))
                assertEquals(ScheduleConflictKind.WORK_SESSION, pending.confirmation.conflicts.single().kind)
                assertEquals(task.title, pending.confirmation.conflicts.single().title)

                val series = assertIs<FixedEventSaveResult.Saved>(
                    eventUseCases.create(createDraft, pending.confirmation),
                ).event
                // 事件写入同样递增共享修订号
                assertEquals(2L, taskUseCases.revision.value)

                // 单次改期后再按记录 ID 重编
                val override = assertIs<FixedEventSaveResult.Saved>(
                    eventUseCases.update(
                        target = FixedEventTarget.Occurrence(series.id, "2026-11-04"),
                        draft = FixedEventDraft(
                            title = "实验答疑",
                            startsAt = at("2026-11-04", 14),
                            endsAt = at("2026-11-04", 15, 30),
                            workspaceId = workspace.id,
                        ),
                        scope = EditScope.THIS_OCCURRENCE,
                    ),
                ).event
                val detailById = assertNotNull(eventUseCases.getDetail(FixedEventTarget.SingleOrSeries(override.id)))
                assertTrue(detailById.supportsScopeSelection)
                assertEquals("2026-11-23", detailById.initialDraft.repeatUntil)

                // 查询 2026-11-02 所在周（周一..周日）
                val week = eventUseCases.queryCalendar(
                    CalendarQuery(
                        mode = CalendarViewMode.WEEK,
                        anchorDate = "2026-11-04",
                        workspaceFilter = CalendarWorkspaceFilter.Workspace(workspace.id),
                    ),
                    nowMillis = at("2026-11-02", 8),
                )
                assertEquals(7, week.days.size)
                assertEquals(
                    setOf(CalendarBlockKind.FIXED_EVENT, CalendarBlockKind.WORK_SESSION),
                    week.blocks.map { it.kind }.toSet(),
                )
                assertTrue(week.blocks.any { it.key == "${series.id}@2026-11-04" && it.title == "实验答疑" })

                // 删除单次与整条序列
                assertTrue(eventUseCases.delete(FixedEventTarget.Occurrence(series.id, "2026-11-09"), EditScope.THIS_OCCURRENCE))
                assertTrue(eventUseCases.delete(FixedEventTarget.SingleOrSeries(series.id)))
                assertNull(sources.fixedEvents.findEventById(series.id))
                assertNull(sources.fixedEvents.findEventById(override.id))
            } finally {
                db.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun sqliteAbortDuringThisAndFutureUpdateRollsBackTruncationAndOverrideCleanupAtomically() = runTest {
        val directory = Files.createTempDirectory("kaixu-fixed-rollback").toFile()
        val file = directory.resolve("rollback.db")
        try {
            val db = openDesktopDatabase(file)
            try {
                val sources = LocalDataSources(db)
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
                val useCases = FixedEventUseCases(repo, defaultZone = shanghai)

                val series = assertIs<FixedEventSaveResult.Saved>(
                    useCases.create(
                        FixedEventDraft(
                            title = "操作系统",
                            startsAt = at("2026-11-02", 9),
                            endsAt = at("2026-11-02", 10),
                            repeatWeekdays = setOf(1),
                            repeatUntil = "2026-11-30",
                        ),
                    ),
                ).event
                val futureOverride = assertIs<FixedEventSaveResult.Saved>(
                    useCases.update(
                        target = FixedEventTarget.Occurrence(series.id, "2026-11-23"),
                        draft = FixedEventDraft(
                            title = "原周三补课",
                            startsAt = at("2026-11-23", 15),
                            endsAt = at("2026-11-23", 16),
                        ),
                        scope = EditScope.THIS_OCCURRENCE,
                    ),
                ).event
                val revisionBeforeAbort = useCases.revision.value

                db.useWriterConnection { conn ->
                    conn.execSQL(
                        "CREATE TRIGGER abort_series_insert BEFORE INSERT ON fixed_events BEGIN SELECT RAISE(ABORT, 'simulated insert failure'); END",
                    )
                }

                val rejected = assertFailsWith<SQLiteException> {
                    useCases.update(
                        target = FixedEventTarget.Occurrence(series.id, "2026-11-16"),
                        draft = FixedEventDraft(
                            title = "改期后的操作系统",
                            startsAt = at("2026-11-16", 14),
                            endsAt = at("2026-11-16", 15),
                            repeatWeekdays = setOf(1),
                            repeatUntil = "2026-11-30",
                        ),
                        scope = EditScope.THIS_AND_FUTURE,
                    )
                }
                assertContains(rejected.message.orEmpty(), "simulated insert failure")

                // 原序列 repeatUntil、已存在的 11-23 例外、内存快照与 revision 均保持原状
                assertEquals("2026-11-30", sources.fixedEvents.findEventById(series.id)?.repeatUntil)
                assertNotNull(sources.fixedEvents.findEventById(futureOverride.id))
                assertEquals("2026-11-30", useCases.observe().value.first { it.id == series.id }.repeatUntil)
                assertEquals(revisionBeforeAbort, useCases.revision.value)
            } finally {
                db.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
