package com.kaixu.domain.usecase.scheduling

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.kaixu.app.di.LocalSchedulingGraph
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.database.openDesktopDatabase
import com.kaixu.domain.model.scheduling.*
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.tasks.TaskStatus
import com.kaixu.domain.model.workspaces.WorkspaceKind
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LocalSchedulingUseCasesTest {
    private val owner = "00000000-0000-0000-0000-000000000001"
    private fun dateAt(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString()

    private suspend fun withStore(block: suspend (File, LocalDataSources, LocalSchedulingGraph) -> Unit) {
        val directory = Files.createTempDirectory("kaixu-scheduling-usecases").toFile()
        try {
            val file = directory.resolve("test.db")
            val db = openDesktopDatabase(file)
            try {
                val sources = LocalDataSources(db)
                block(file, sources, LocalSchedulingGraph(sources, owner, ::dateAt))
            } finally { db.close() }
        } finally { directory.deleteRecursively() }
    }

    private fun saved(result: WorkSessionSaveResult): List<WorkSession> = assertIs<WorkSessionSaveResult.Saved>(result).sessions
    private fun review(result: WorkSessionSaveResult): WorkSessionConfirmation = assertIs<WorkSessionSaveResult.NeedsConfirmation>(result).confirmation

    @Test
    fun conflictsDeadlinesAndBatchOverlapsRequireAnUnchangedConfirmation() = runTest {
        withStore { _, sources, graph ->
            val task = sources.tasks.createTask(Task(title = "任务", deadlineAt = 1000))
            sources.fixedEvents.createEvent(FixedEvent("event", "课程", 100, 200))
            val drafts = listOf(WorkSessionDraft(task.id, 0, 500, id = "a"), WorkSessionDraft(task.id, 400, 1200, id = "b"))
            val first = review(graph.createWorkSessions(drafts))
            assertEquals(listOf("event"), first.events.map { it.id })
            assertEquals(listOf(ProposedSessionOverlap("a", "b")), first.proposedOverlaps)
            assertEquals(listOf("b"), first.pastDeadlineIds)
            assertTrue(graph.workSessionRepository.listByTask(task.id).isEmpty())
            sources.fixedEvents.createEvent(FixedEvent("new-event", "新增占用", 600, 700))
            val second = review(graph.createWorkSessions(drafts, first))
            assertEquals(2, second.events.size)
            assertTrue(graph.workSessionRepository.listByTask(task.id).isEmpty())
            val created = saved(graph.createWorkSessions(drafts, second))
            assertTrue(created.all { it.overlapConfirmed })
            assertEquals(created, saved(graph.createWorkSessions(drafts, second)))
            assertEquals(2, graph.getPersonalSchedule(0, 1500).workSessions.size)
            assertEquals(2, graph.getPersonalSchedule(0, 1500).events.size)
        }
    }

    @Test
    fun adjacencyCancellationAndCalendarFilteringUseHalfOpenRanges() = runTest {
        withStore { _, sources, graph ->
            val task = sources.tasks.createTask("任务")
            val drafts = listOf(WorkSessionDraft(task.id, 0, 1000, id = "a"), WorkSessionDraft(task.id, 1000, 2000, id = "b"))
            val plans = saved(graph.createWorkSessions(drafts))
            assertTrue(plans.none { it.overlapConfirmed })
            graph.changeWorkSessionStatus(plans[0], WorkSessionStatus.CANCELLED)
            assertEquals(listOf("b"), graph.getPersonalSchedule(0, 3000).workSessions.map { it.id })
            assertEquals(2, graph.workSessionRepository.listByTask(task.id).size)
            assertEquals(listOf("b"), graph.workSessionRepository.listInRange(1000, 2000).map { it.id })
            assertFails { graph.changeWorkSessionStatus(plans[1], WorkSessionStatus.PLANNED) }
        }
    }

    @Test
    fun dateOnlyDeadlineIncludesItsWholeDayInTheSelectedTimeZone() = runTest {
        withStore { _, sources, graph ->
            val task = sources.tasks.createTask(Task(title = "日期截止", deadlineDate = "2026-11-11"))
            // 上海 11 月 12 日零点；结束等于零点仍然全部发生在截止日内。
            val midnight = Instant.parse("2026-11-11T16:00:00Z").toEpochMilli()
            saved(graph.createWorkSessions(listOf(WorkSessionDraft(task.id, midnight - 1000, midnight, id = "on-time"))))
            val late = review(graph.createWorkSessions(listOf(WorkSessionDraft(task.id, midnight, midnight + 1000, id = "late"))))
            assertEquals(listOf("late"), late.pastDeadlineIds)
        }
    }

    @Test
    fun rejectsCompletedArchivedMissingAndForeignTasksBeforeWriting() = runTest {
        withStore { _, sources, graph ->
            val complete = sources.tasks.createTask(Task(title = "完成", status = TaskStatus.COMPLETED, completedAt = 1))
            val space = sources.workspaces.createWorkspace(WorkspaceKind.PROJECT, "项目")
            val archived = sources.tasks.createTask(Task(title = "归档", workspaceId = space.id))
            sources.workspaces.archiveWorkspace(space.id)
            val foreign = sources.tasks.createTask(Task(title = "他人任务", createdBy = "bob"))
            for (id in listOf(complete.id, archived.id, foreign.id, "missing")) {
                assertFails { graph.createWorkSessions(listOf(WorkSessionDraft(id, 0, 1000))) }
            }
            assertTrue(sources.workSessions.listWorkSessions().isEmpty())
            assertNull(graph.workSessionRepository.findTaskById(foreign.id))
        }
    }

    @Test
    fun editsRejectStaleSnapshotsAndPreserveOtherPlans() = runTest {
        withStore { _, sources, graph ->
            val task = sources.tasks.createTask("任务")
            val plans = saved(graph.createWorkSessions(listOf(WorkSessionDraft(task.id, 0, 1000, id = "a"), WorkSessionDraft(task.id, 2000, 3000, id = "b"))))
            val draft = WorkSessionDraft(task.id, 1000, 2000, id = "a", note = "改期", isLocked = true)
            val edited = saved(graph.updateWorkSession(plans[0], draft)).single()
            assertTrue(edited.isLocked)
            assertEquals("改期", edited.note)
            assertFails { graph.updateWorkSession(plans[0], draft.copy(note = "过期覆盖")) }
            assertFails { graph.deleteWorkSession(plans[0]) }
            assertTrue(graph.deleteWorkSession(edited))
            assertFalse(graph.deleteWorkSession(edited))
            assertEquals(plans[1], graph.workSessionRepository.findById("b"))
            assertNotNull(sources.tasks.findTaskById(task.id))
        }
    }

    @Test
    fun repositoriesRestrictPrivateReadsAndWritesToTheirBoundOwner() = runTest {
        withStore { _, sources, graph ->
            val task = sources.tasks.createTask("任务")
            val foreignPlan = WorkSession("bob-plan", task.id, 0, 1000, ownerUserId = "bob")
            sources.workSessions.createWorkSession(foreignPlan)
            val foreignFocus = FocusSession("bob-focus", foreignPlan.id, 0, ownerUserId = "bob")
            sources.focus.createFocusSession(foreignFocus)
            val segment = FocusSegment("g", foreignFocus.id, "key", 0, 1000, FocusMode.FOREGROUND)
            sources.focus.appendFocusSegment(segment)
            assertNull(graph.workSessionRepository.findById(foreignPlan.id))
            assertTrue(graph.workSessionRepository.listByTask(task.id).isEmpty())
            assertTrue(graph.workSessionRepository.listInRange(0, 1000).isEmpty())
            assertFalse(graph.workSessionRepository.deleteById(foreignPlan.id))
            assertFails { graph.workSessionRepository.update(foreignPlan) }
            assertFails { graph.workSessionRepository.createAll(listOf(foreignPlan.copy(id = "another"))) }
            assertNull(graph.focusRepository.findSessionById(foreignFocus.id))
            assertTrue(graph.focusRepository.listSessions(foreignPlan.id).isEmpty())
            assertTrue(graph.focusRepository.listSegments(foreignFocus.id).isEmpty())
            assertEquals(FocusDuration(), graph.focusRepository.getDuration(foreignPlan.id))
            assertFails { graph.focusRepository.appendSegment(segment) }
            assertFails { graph.focusRepository.updateSession(foreignFocus) }
            assertFails { graph.startFocusSession(foreignPlan.id, 0) }
        }
    }

    @Test
    fun pauseResumeFinishRetriesAndWindowOutsideFragmentsPreserveActualDuration() = runTest {
        withStore { _, sources, graph ->
            val task = sources.tasks.createTask("任务")
            val plan = saved(graph.createWorkSessions(listOf(WorkSessionDraft(task.id, 1000, 61000, id = "plan")))).single()
            val session = graph.startFocusSession(plan.id, 0, sessionId = "focus")
            assertEquals(60, graph.workSessionRepository.findById(plan.id)?.focusTargetSeconds)
            assertEquals(session, graph.startFocusSession(plan.id, 0, sessionId = "focus"))
            val first = FocusSegment("g1", session.id, "first", 0, 10000, FocusMode.FOREGROUND)
            val paused = graph.controlFocusSession.pause(session.id, first)
            assertEquals(FocusSessionStatus.PAUSED, paused.status)
            assertEquals(paused, graph.controlFocusSession.pause(session.id, first.copy(id = "retry")))
            assertEquals(first, graph.recordFocusSegment(first.copy(id = "retry")))
            assertFails { graph.recordFocusSegment(first.copy(id = "new", clientSegmentId = "new", startsAt = 10000, endsAt = 20000)) }
            graph.controlFocusSession.resume(session.id)
            val last = FocusSegment("g2", session.id, "last", 70000, 90000, FocusMode.BACKGROUND)
            val ended = graph.controlFocusSession.finish(session.id, 90000, feedback = "完成一轮", finalSegment = last)
            assertEquals(ended, graph.controlFocusSession.finish(session.id, 90000, feedback = "完成一轮", finalSegment = last))
            assertEquals(last, graph.recordFocusSegment(last.copy(id = "repeat-last")))
            assertFails { graph.controlFocusSession.resume(session.id) }
            assertFails { graph.controlFocusSession.finish(session.id, 100000) }
            val another = graph.startFocusSession(plan.id, 100000, sessionId = "second")
            graph.controlFocusSession.finish(another.id, 100000, interrupted = true)
            val summary = graph.getFocusSummary(plan.id)
            assertEquals(2, summary.sessions.size)
            assertEquals(FocusDuration(10000, 20000), summary.duration)
            assertEquals(TaskStatus.TODO, sources.tasks.findTaskById(task.id)?.status)
            assertEquals(WorkSessionStatus.PLANNED, graph.workSessionRepository.findById(plan.id)?.status)
            assertTrue(graph.deleteWorkSession(assertNotNull(graph.workSessionRepository.findById(plan.id))))
            assertEquals(summary, graph.getFocusSummary(plan.id))
        }
    }

    @Test
    fun failedFinishRollsBackTheFinalSegmentAndLeavesTheSessionRunning() = runTest {
        withStore { file, sources, graph ->
            val task = sources.tasks.createTask("任务")
            val plan = saved(graph.createWorkSessions(listOf(WorkSessionDraft(task.id, 0, 1000, id = "p")))).single()
            val session = graph.startFocusSession(plan.id, 0, sessionId = "f")
            val last = FocusSegment("g", session.id, "last", 0, 1000, FocusMode.FOREGROUND)
            BundledSQLiteDriver().open(file.absolutePath).use { connection ->
                connection.execSQL("""CREATE TRIGGER fail_finish BEFORE UPDATE ON focus_sessions
                    BEGIN SELECT RAISE(ABORT, 'simulated failure'); END""")
            }
            assertFails { graph.controlFocusSession.finish(session.id, 1000, finalSegment = last) }
            assertTrue(graph.focusRepository.listSegments(session.id).isEmpty())
            assertEquals(FocusSessionStatus.ACTIVE, graph.focusRepository.findSessionById(session.id)?.status)
            BundledSQLiteDriver().open(file.absolutePath).use { it.execSQL("DROP TRIGGER fail_finish") }
            assertFails { graph.controlFocusSession.finish(session.id, 500, finalSegment = last) }
            assertTrue(graph.focusRepository.listSegments(session.id).isEmpty())
            graph.controlFocusSession.finish(session.id, 1000, finalSegment = last)
            assertEquals(1000, graph.getFocusSummary(plan.id).duration.totalMillis)
        }
    }

    @Test
    fun failedSecondStartRollsBackItsDefaultTargetAndResumeRechecksTheTask() = runTest {
        withStore { _, sources, graph ->
            val task = sources.tasks.createTask("任务")
            val plans = saved(graph.createWorkSessions(listOf(WorkSessionDraft(task.id, 0, 1000, id = "a"), WorkSessionDraft(task.id, 1000, 2000, id = "b"))))
            val session = graph.startFocusSession(plans[0].id, 0, sessionId = "focus")
            assertFails { graph.startFocusSession(plans[1].id, 1000, sessionId = "parallel") }
            assertNull(graph.workSessionRepository.findById(plans[1].id)?.focusTargetSeconds)
            graph.controlFocusSession.pause(session.id)
            sources.tasks.updateTask(task.copy(status = TaskStatus.COMPLETED, completedAt = 1000))
            assertFails { graph.controlFocusSession.resume(session.id) }
            assertEquals(FocusSessionStatus.PAUSED, graph.focusRepository.findSessionById(session.id)?.status)
            graph.controlFocusSession.finish(session.id, 1000)
            assertFails { graph.startFocusSession(plans[1].id, 1000, sessionId = "completed") }
        }
    }

    @Test
    fun creationRetriesCannotOverwriteExistingContentOrPartiallyInsertNewPlans() = runTest {
        withStore { _, sources, graph ->
            val task = sources.tasks.createTask("任务")
            val draft = WorkSessionDraft(task.id, 0, 1000, id = "stable")
            val original = saved(graph.createWorkSessions(listOf(draft)))
            assertEquals(original, saved(graph.createWorkSessions(listOf(draft))))
            assertFails { graph.createWorkSessions(listOf(draft.copy(note = "不同内容"))) }
            assertFails { graph.createWorkSessions(listOf(draft, draft.copy(id = "new", startsAt = 1000, endsAt = 2000))) }
            assertNull(graph.workSessionRepository.findById("new"))
            assertEquals(original, graph.workSessionRepository.listByTask(task.id))
        }
    }

    @Test
    fun changingADeadlineInvalidatesConfirmationAndStartedTargetsCannotBeReduced() = runTest {
        withStore { _, sources, graph ->
            val task = sources.tasks.createTask(Task(title = "任务", deadlineAt = 500))
            val draft = WorkSessionDraft(task.id, 0, 1000, id = "plan", focusTargetSeconds = 60)
            val first = review(graph.createWorkSessions(listOf(draft)))
            sources.tasks.updateTask(task.copy(deadlineAt = 400))
            val second = review(graph.createWorkSessions(listOf(draft), first))
            assertNotEquals(first.tasks, second.tasks)
            val plan = saved(graph.createWorkSessions(listOf(draft), second)).single()
            graph.startFocusSession(plan.id, 2000, sessionId = "focus")
            assertFails { graph.updateWorkSession(plan, draft.copy(focusTargetSeconds = 1)) }
            graph.controlFocusSession.finish("focus", 2000)
            assertFails { graph.updateWorkSession(plan, draft.copy(focusTargetSeconds = 1)) }
            // 时间和说明仍可调整，且不会据新计划窗口改写实际计时。
            val updated = saved(graph.updateWorkSession(plan, draft.copy(startsAt = 100, endsAt = 300))).single()
            assertEquals(60, updated.focusTargetSeconds)
            assertEquals(2000, graph.focusRepository.findSessionById("focus")?.startedAt)
        }
    }
}
