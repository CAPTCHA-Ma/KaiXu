package com.kaixu.domain.usecase.scheduling

import androidx.room.execSQL
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteException
import com.kaixu.app.di.AppGraph
import com.kaixu.core.platform.LocalZone
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.database.openDesktopDatabase
import com.kaixu.domain.model.scheduling.CalendarBlockKind
import com.kaixu.domain.model.scheduling.CalendarQuery
import com.kaixu.domain.model.scheduling.CalendarViewMode
import com.kaixu.domain.model.scheduling.CalendarWindowSnapshot
import com.kaixu.domain.model.scheduling.EditScope
import com.kaixu.domain.model.scheduling.FixedEventDraft
import com.kaixu.domain.model.scheduling.FixedEventTarget
import com.kaixu.domain.model.scheduling.FocusDuration
import com.kaixu.domain.model.scheduling.FocusMode
import com.kaixu.domain.model.scheduling.FocusSegment
import com.kaixu.domain.model.scheduling.FocusSessionStatus
import com.kaixu.domain.model.scheduling.WorkSessionConflictKind
import com.kaixu.domain.model.scheduling.WorkSessionDraft
import com.kaixu.domain.model.scheduling.WorkSessionSaveResult
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.model.tasks.CompleteTaskResult
import com.kaixu.domain.model.tasks.FutureSessionPolicy
import com.kaixu.domain.model.tasks.TaskDeadline
import com.kaixu.domain.model.tasks.TaskDraft
import com.kaixu.domain.model.tasks.TaskListSnapshot
import com.kaixu.domain.model.tasks.TaskQuery
import com.kaixu.domain.model.tasks.TaskScheduleState
import com.kaixu.domain.model.workspaces.WorkspaceKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkSessionUseCasesDatabaseTest {
    private val shanghai = LocalZone.of("Asia/Shanghai")

    private fun at(date: String, hour: Int, minute: Int = 0): Long =
        shanghai.atLocalDateTimeMillis(date, (hour * 60L + minute) * 60_000L)

    @Test
    fun appGraphSharesRevisionAcrossTasksEventsWorkSessionsAndFocusOnRoom() = runTest {
        val directory = Files.createTempDirectory("kaixu-work-session-usecases").toFile()
        val file = directory.resolve("app-graph-sessions.db")
        try {
            val db = openDesktopDatabase(file)
            try {
                val sources = LocalDataSources(db)
                val graph = AppGraph(sources)
                val taskUseCases = graph.taskUseCases
                val eventUseCases = graph.fixedEventUseCases
                val sessionUseCases = graph.workSessionUseCases
                val focusUseCases = graph.focusUseCases

                val workspace = sources.workspaces.createWorkspace(WorkspaceKind.COURSE, "计算机网络")
                val task = taskUseCases.createFromDraft(
                    TaskDraft(
                        title = "路由协议大作业",
                        workspaceId = workspace.id,
                        deadline = TaskDeadline.DateOnly("2026-11-11"),
                    ),
                )

                val latestCalendar = MutableStateFlow<CalendarWindowSnapshot?>(null)
                val latestTasks = MutableStateFlow<TaskListSnapshot?>(null)
                val calendarJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    eventUseCases.queryCalendar.observe(
                        queryFlow = MutableStateFlow(CalendarQuery(CalendarViewMode.WEEK, "2026-11-11")),
                        clock = { at("2026-11-09", 8) },
                        zone = shanghai,
                    ).collect { snapshot ->
                        latestCalendar.value = snapshot
                    }
                }
                val taskJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    taskUseCases.queryTasks.observe(
                        queryFlow = MutableStateFlow(TaskQuery()),
                        clock = { at("2026-11-09", 8) },
                        zone = shanghai,
                    ).collect { snapshot ->
                        latestTasks.value = snapshot
                    }
                }

                try {
                    val initialTaskSnapshot = latestTasks.filterNotNull().first()
                    assertEquals(
                        TaskScheduleState.UNSCHEDULED,
                        initialTaskSnapshot.items.first { it.task.id == task.id }.scheduleState,
                    )
                    assertEquals(
                        0,
                        latestCalendar.filterNotNull().first().blocks.count { it.kind == CalendarBlockKind.WORK_SESSION },
                    )

                    // 创建每周一、三 10:00-11:30 重复课程（起始于 11-02，跨周至 11-30）
                    val series = assertIs<FixedEventSaveResult.Saved>(
                        eventUseCases.create(
                            FixedEventDraft(
                                title = "计网理论课",
                                startsAt = at("2026-11-02", 10, 0),
                                endsAt = at("2026-11-02", 11, 30),
                                workspaceId = workspace.id,
                                repeatWeekdays = setOf(1, 3),
                                repeatUntil = "2026-11-30",
                            ),
                            zone = shanghai,
                        ),
                    ).event

                    // 取消 11-09 周一单次课次，并将 11-11 周三课次改期到 15:00-16:30
                    assertTrue(
                        eventUseCases.delete(
                            target = FixedEventTarget.Occurrence(series.id, "2026-11-09"),
                            scope = EditScope.THIS_OCCURRENCE,
                            zone = shanghai,
                        ),
                    )
                    assertIs<FixedEventSaveResult.Saved>(
                        eventUseCases.update(
                            target = FixedEventTarget.Occurrence(series.id, "2026-11-11"),
                            draft = FixedEventDraft(
                                title = "计网实验课",
                                startsAt = at("2026-11-11", 15, 0),
                                endsAt = at("2026-11-11", 16, 30),
                                workspaceId = workspace.id,
                            ),
                            scope = EditScope.THIS_OCCURRENCE,
                            zone = shanghai,
                        ),
                    )

                    // 11-09 10:00（已取消占位）直接无冲突创建个人时段；同时自动触发 TaskUseCases 与 CalendarWindow 响应式更新
                    val s1 = assertIs<WorkSessionSaveResult.Saved>(
                        sessionUseCases.create(
                            WorkSessionDraft(
                                id = "ws-mon",
                                taskId = task.id,
                                startsAt = at("2026-11-09", 10, 0),
                                endsAt = at("2026-11-09", 11, 30),
                            ),
                            zone = shanghai,
                        ),
                    ).session
                    val scheduledTaskItem = latestTasks
                        .filterNotNull()
                        .mapNotNull { it.items.firstOrNull { item -> item.task.id == task.id } }
                        .first { it.scheduleState == TaskScheduleState.HAS_SESSIONS }
                    assertEquals(TaskScheduleState.HAS_SESSIONS, scheduledTaskItem.scheduleState)
                    val calendarAfterFirstSession = latestCalendar
                        .filterNotNull()
                        .first { it.blocks.count { b -> b.kind == CalendarBlockKind.WORK_SESSION } == 1 }
                    assertEquals(
                        1,
                        calendarAfterFirstSession.blocks.count { it.kind == CalendarBlockKind.WORK_SESSION },
                    )

                    // 11-11 15:30 命中改期后的例外课次冲突，确认后保存并支持拖拽改期
                    val conflictReview = assertIs<WorkSessionSaveResult.NeedsConfirmation>(
                        sessionUseCases.create(
                            WorkSessionDraft(
                                id = "ws-wed",
                                taskId = task.id,
                                startsAt = at("2026-11-11", 15, 30),
                                endsAt = at("2026-11-11", 17, 0),
                            ),
                            zone = shanghai,
                        ),
                    ).confirmation
                    assertEquals(WorkSessionConflictKind.FIXED_EVENT, conflictReview.conflicts.single().kind)
                    assertEquals("${series.id}@2026-11-11", conflictReview.conflicts.single().key)
                    assertEquals("计网实验课", conflictReview.conflicts.single().title)

                    val s2 = assertIs<WorkSessionSaveResult.Saved>(
                        sessionUseCases.create(
                            WorkSessionDraft(
                                id = "ws-wed",
                                taskId = task.id,
                                startsAt = at("2026-11-11", 15, 30),
                                endsAt = at("2026-11-11", 17, 0),
                            ),
                            confirmed = conflictReview,
                            zone = shanghai,
                        ),
                    ).session
                    assertTrue(s2.overlapConfirmed)
                    val calendarAfterSecondSession = latestCalendar
                        .filterNotNull()
                        .first { it.blocks.count { b -> b.kind == CalendarBlockKind.WORK_SESSION } == 2 }
                    assertEquals(
                        2,
                        calendarAfterSecondSession.blocks.count { it.kind == CalendarBlockKind.WORK_SESSION },
                    )

                    // 在 s1 上启动计时并记录有效片段；随后直接删除任务触发原子中断计时与时段脱链
                    val focus = focusUseCases.start(s1.id, startedAt = at("2026-11-09", 10, 0), sessionId = "focus-mon")
                    focusUseCases.recordSegment(
                        FocusSegment("seg-mon", focus.id, "c-mon", at("2026-11-09", 10, 0), at("2026-11-09", 10, 30), FocusMode.FOREGROUND),
                    )

                    assertTrue(taskUseCases.delete(task.id))

                    val interrupted = assertNotNull(focusUseCases.findSessionById(focus.id))
                    assertEquals(FocusSessionStatus.INTERRUPTED, interrupted.status)

                    val detachedDetail = assertNotNull(sessionUseCases.getDetail(s1.id))
                    assertTrue(detachedDetail.isDetached)
                    assertEquals("TASK_DELETED", detachedDetail.session.detachReason)
                    assertFalse(detachedDetail.canEdit)
                    assertFalse(detachedDetail.canReschedule)
                    assertFalse(detachedDetail.canStartFocus)
                    assertTrue(detachedDetail.canDelete)
                    assertEquals(FocusDuration(30 * 60_000L, 0L), detachedDetail.focusDuration)

                    assertTrue(sessionUseCases.delete(s1.id))
                    assertNull(sessionUseCases.getDetail(s1.id))
                    assertEquals(30 * 60_000L, focusUseCases.getSummary(s1.id).duration.totalMillis)
                } finally {
                    calendarJob.cancelAndJoin()
                    taskJob.cancelAndJoin()
                }
            } finally {
                db.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun keepFutureOnTaskCompletionAllowsCompletingSessionWithoutAlteringTaskAndSqliteAbortRollsBackBatch() = runTest {
        val directory = Files.createTempDirectory("kaixu-work-session-rollback").toFile()
        val file = directory.resolve("rollback.db")
        try {
            val db = openDesktopDatabase(file)
            try {
                val sources = LocalDataSources(db)
                val graph = AppGraph(sources)
                val now = at("2026-11-10", 8, 0)

                val task = graph.taskUseCases.createFromDraft(TaskDraft(title = "复习编译原理"))
                val futureSession = assertIs<WorkSessionSaveResult.Saved>(
                    graph.workSessionUseCases.create(
                        WorkSessionDraft(
                            id = "ws-future",
                            taskId = task.id,
                            startsAt = at("2026-11-10", 14, 0),
                            endsAt = at("2026-11-10", 15, 0),
                        ),
                        zone = shanghai,
                    ),
                ).session

                val completedTask = assertIs<CompleteTaskResult.Completed>(
                    graph.taskUseCases.complete(task.id, nowMillis = now, policy = FutureSessionPolicy.KEEP_FUTURE),
                )
                assertEquals(1, completedTask.retainedFutureCount)

                // 任务完成后通过 KEEP_FUTURE 保留的个人时段不可继续编辑/拖拽，但允许用户结束该个人安排
                val keptDetail = assertNotNull(graph.workSessionUseCases.getDetail(futureSession.id))
                assertFalse(keptDetail.canEdit)
                assertFalse(keptDetail.canReschedule)
                assertTrue(keptDetail.canComplete)
                assertTrue(keptDetail.canCancel)
                assertEquals(
                    WorkSessionStatus.COMPLETED,
                    graph.workSessionUseCases.changeStatus(futureSession.id, WorkSessionStatus.COMPLETED).status,
                )

                // 验证批量创建中途触发 SQLite 异常时整体回滚且 revision 不递增
                val anotherTask = graph.taskUseCases.createFromDraft(TaskDraft(title = "批量回滚验证"))
                val revBeforeAbort = graph.localRevision.version.value
                db.useWriterConnection { conn ->
                    conn.execSQL(
                        """CREATE TRIGGER abort_second_session BEFORE INSERT ON work_sessions
                        WHEN NEW.id = 'batch-2'
                        BEGIN SELECT RAISE(ABORT, 'simulated batch failure'); END""",
                    )
                }

                val ex = assertFailsWith<SQLiteException> {
                    graph.workSessionUseCases.create(
                        listOf(
                            WorkSessionDraft(anotherTask.id, at("2026-11-12", 9), at("2026-11-12", 10), id = "batch-1"),
                            WorkSessionDraft(anotherTask.id, at("2026-11-12", 10), at("2026-11-12", 11), id = "batch-2"),
                        ),
                        zone = shanghai,
                    )
                }
                assertContains(ex.message.orEmpty(), "simulated batch failure")
                assertTrue(graph.workSessionUseCases.listByTask(anotherTask.id).isEmpty())
                assertEquals(revBeforeAbort, graph.localRevision.version.value)
            } finally {
                db.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
