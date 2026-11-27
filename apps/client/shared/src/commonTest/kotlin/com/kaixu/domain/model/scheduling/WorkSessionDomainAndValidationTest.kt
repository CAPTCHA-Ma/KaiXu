package com.kaixu.domain.model.scheduling

import com.kaixu.core.platform.LocalZone
import com.kaixu.domain.model.tasks.TaskDeadline
import com.kaixu.domain.validation.scheduling.SchedulingInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkSessionDomainAndValidationTest {
    private val shanghai = LocalZone.of("Asia/Shanghai")
    private val newYork = LocalZone.of("America/New_York")

    @Test
    fun draftsNormalizeBlankNotesAndRejectInvalidRangesIdsOrTargets() {
        val draft = WorkSessionDraft(
            taskId = "task-1",
            startsAt = 1_000L,
            endsAt = 2_000L,
            id = "ws-1",
            note = "   复习算法   ",
            isLocked = true,
            focusTargetSeconds = 120L,
        )
        val clean = SchedulingInput.normalizedDraft(draft)
        assertEquals("复习算法", clean.note)

        val blankNote = SchedulingInput.toNewWorkSession(draft.copy(note = "   "), ownerUserId = "alice")
        assertNull(blankNote.note)
        assertEquals("alice", blankNote.ownerUserId)

        assertFailsWith<IllegalArgumentException> {
            SchedulingInput.normalizedDraft(draft.copy(id = " "))
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingInput.normalizedDraft(draft.copy(taskId = ""))
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingInput.normalizedDraft(draft.copy(startsAt = 2_000L, endsAt = 2_000L))
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingInput.normalizedDraft(draft.copy(focusTargetSeconds = 0L))
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingInput.normalizedEditDraft(WorkSessionEditDraft(startsAt = 500L, endsAt = 100L))
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingInput.normalizedEditDraft(WorkSessionEditDraft(startsAt = 100L, endsAt = 500L, focusTargetSeconds = -1L))
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingInput.normalizedReschedule(WorkSessionReschedule(startsAt = 100L, endsAt = 100L))
        }
    }

    @Test
    fun detachmentHelpersAndInvariantsEnforceConsistentTaskLinking() {
        val attached = WorkSession(
            id = "ws-attached",
            taskId = "task-1",
            startsAt = 1_000L,
            endsAt = 4_000L,
            note = "说明",
            focusTargetSeconds = 60L,
        )
        assertFalse(attached.isDetached)
        assertEquals(3_000L, attached.plannedDurationMillis)
        assertEquals("task-1", attached.toDraftOrNull()?.taskId)
        assertEquals(60L, attached.toEditDraft().focusTargetSeconds)

        val detached = SchedulingInput.workSession(
            WorkSession(
                id = "ws-detached",
                taskId = null,
                startsAt = 1_000L,
                endsAt = 4_000L,
                detachedAt = 5_000L,
                detachReason = "TASK_DELETED",
            ),
        )
        assertTrue(detached.isDetached)
        assertNull(detached.toDraftOrNull())

        assertFailsWith<IllegalArgumentException> {
            SchedulingInput.workSession(attached.copy(detachedAt = 1L))
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingInput.workSession(detached.copy(stageId = "stage-1"))
        }
    }

    @Test
    fun halfOpenIntervalsAndDeadlineChecksRespectZoneMidnightAndExactTimestamps() {
        assertFalse(SchedulingInput.intervalsOverlap(0L, 1_000L, 1_000L, 2_000L))
        assertTrue(SchedulingInput.intervalsOverlap(0L, 1_001L, 1_000L, 2_000L))

        assertFalse(SchedulingInput.exceedsDeadline(0L, Long.MAX_VALUE, TaskDeadline.None, shanghai))

        val exact = TaskDeadline.ExactTime(5_000L)
        assertFalse(SchedulingInput.exceedsDeadline(1_000L, 5_000L, exact, shanghai))
        assertTrue(SchedulingInput.exceedsDeadline(1_000L, 5_001L, exact, shanghai))

        val dateOnly = TaskDeadline.DateOnly("2026-11-11")
        val shanghaiNextMidnight = shanghai.startOfNextDayMillis("2026-11-11")
        assertFalse(SchedulingInput.exceedsDeadline(shanghaiNextMidnight - 3_600_000L, shanghaiNextMidnight, dateOnly, shanghai))
        assertTrue(SchedulingInput.exceedsDeadline(shanghaiNextMidnight - 3_600_000L, shanghaiNextMidnight + 1L, dateOnly, shanghai))

        // 同一 UTC 时刻在纽约仍处于 2026-11-11 白天，不应在纽约时区误判超期
        assertFalse(SchedulingInput.exceedsDeadline(shanghaiNextMidnight - 3_600_000L, shanghaiNextMidnight + 1L, dateOnly, newYork))
    }

    @Test
    fun confirmationModelExposesTypedViewsOverConflictsAndWarnings() {
        val confirmation = WorkSessionConfirmation(
            confirmationKey = "key-1",
            proposed = listOf(
                WorkSession("a", "t-1", 0L, 500L),
                WorkSession("b", "t-1", 400L, 1_200L),
            ),
            conflicts = listOf(
                WorkSessionConflictItem(
                    kind = WorkSessionConflictKind.FIXED_EVENT,
                    key = "series@2026-11-11",
                    recordId = "series",
                    title = "高数",
                    startsAt = 100L,
                    endsAt = 200L,
                    isRecurringOccurrence = true,
                    proposedSessionIds = listOf("a"),
                ),
                WorkSessionConflictItem(
                    kind = WorkSessionConflictKind.WORK_SESSION,
                    key = "existing",
                    recordId = "existing",
                    title = "作业",
                    startsAt = 450L,
                    endsAt = 600L,
                    proposedSessionIds = listOf("a", "b"),
                ),
                WorkSessionConflictItem(
                    kind = WorkSessionConflictKind.PROPOSED_OVERLAP,
                    key = "a:b",
                    recordId = "b",
                    title = "作业",
                    startsAt = 400L,
                    endsAt = 500L,
                    proposedSessionIds = listOf("a", "b"),
                ),
            ),
            deadlineWarnings = listOf(
                WorkSessionDeadlineWarning(
                    sessionId = "b",
                    taskId = "t-1",
                    taskTitle = "作业",
                    deadline = TaskDeadline.ExactTime(1_000L),
                    sessionEndsAt = 1_200L,
                ),
            ),
        )

        assertTrue(confirmation.requiresConfirmation)
        assertEquals(1, confirmation.fixedEventConflicts.size)
        assertTrue(confirmation.fixedEventConflicts.single().isRecurringOccurrence)
        assertEquals(1, confirmation.workSessionConflicts.size)
        assertEquals(listOf(ProposedSessionOverlap("a", "b")), confirmation.proposedOverlaps)
        assertEquals(listOf("b"), confirmation.pastDeadlineIds)
    }
}
