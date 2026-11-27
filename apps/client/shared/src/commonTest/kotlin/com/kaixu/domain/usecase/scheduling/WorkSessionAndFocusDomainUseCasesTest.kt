package com.kaixu.domain.usecase.scheduling

import com.kaixu.core.platform.LocalZone
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.FocusDuration
import com.kaixu.domain.model.scheduling.FocusMode
import com.kaixu.domain.model.scheduling.FocusSegment
import com.kaixu.domain.model.scheduling.FocusSessionStatus
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.scheduling.WorkSessionConflictKind
import com.kaixu.domain.model.scheduling.WorkSessionDraft
import com.kaixu.domain.model.scheduling.WorkSessionEditDraft
import com.kaixu.domain.model.scheduling.WorkSessionReschedule
import com.kaixu.domain.model.scheduling.WorkSessionSaveResult
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.tasks.TaskDeadline
import com.kaixu.domain.model.tasks.TaskStatus
import com.kaixu.domain.model.tasks.withDeadline
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceKind
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.repository.LocalDataRevisionTracker
import com.kaixu.testing.TestFocusRepository
import com.kaixu.testing.TestWorkSessionRepository
import com.kaixu.testing.TestWorkSessionStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkSessionAndFocusDomainUseCasesTest {
    private val owner = "alice"
    private val shanghai = LocalZone.of("Asia/Shanghai")
    private val store = TestWorkSessionStore()
    private val revisionTracker = LocalDataRevisionTracker()
    private val workSessionRepo = TestWorkSessionRepository(
        store = store,
        ownerUserId = owner,
        revisionTracker = revisionTracker,
    )
    private val focusRepo = TestFocusRepository(
        store = store,
        ownerUserId = owner,
        revisionTracker = revisionTracker,
    )
    private val workSessionUseCases = WorkSessionUseCases(workSessionRepo, defaultZone = shanghai)
    private val focusUseCases = FocusUseCases(focusRepo, defaultZone = shanghai, workSessionRepository = workSessionRepo)

    private fun at(date: String, hour: Int, minute: Int = 0): Long =
        shanghai.atLocalDateTimeMillis(date, (hour * 60L + minute) * 60_000L)

    private fun workspace(
        id: String,
        ownerId: String = owner,
        status: WorkspaceStatus = WorkspaceStatus.ACTIVE,
    ) = Workspace(
        id = id,
        ownerUserId = ownerId,
        kind = WorkspaceKind.COURSE,
        name = "课程-$id",
        description = null,
        linkedCourseId = null,
        status = status,
        createdAt = 0L,
        updatedAt = 0L,
        archivedAt = if (status == WorkspaceStatus.ARCHIVED) 1L else null,
    )

    @Test
    fun conflictCheckerExpandsRecurringClassesHonorsOverridesTombstonesAndExcludesSharedReadOnlyEvents() = runTest {
        val space = workspace("w-1")
        store.putWorkspace(space)
        val task = Task(id = "t-1", title = "操作系统实验", createdBy = owner, workspaceId = space.id)
            .withDeadline(TaskDeadline.DateOnly("2026-11-11"))
        val otherTask = Task(id = "t-2", title = "数据库报告", createdBy = owner, workspaceId = space.id)
        store.putTask(task)
        store.putTask(otherTask)

        // 每周一、周三 10:00-11:30 的本人重复课程，起始于 2026-11-02
        val series = FixedEvent(
            id = "series-os",
            title = "操作系统正课",
            startsAt = at("2026-11-02", 10, 0),
            endsAt = at("2026-11-02", 11, 30),
            workspaceId = space.id,
            createdBy = owner,
            repeatWeekdays = setOf(1, 3),
            repeatUntil = "2026-11-30",
        )
        // 2026-11-09（下周一）取消占位：该时段应空闲不报错
        val cancelledMonday = FixedEvent(
            id = "occ-cancel",
            title = "操作系统正课",
            startsAt = at("2026-11-09", 10, 0),
            endsAt = at("2026-11-09", 11, 30),
            createdBy = owner,
            seriesId = series.id,
            originalDate = "2026-11-09",
            isOverride = true,
            isCancelled = true,
        )
        // 2026-11-11（下周三）改期到 14:00-15:30：原 10:00 空闲，新 14:00 冲突
        val movedWednesday = FixedEvent(
            id = "occ-moved",
            title = "操作系统实验答疑",
            startsAt = at("2026-11-11", 14, 0),
            endsAt = at("2026-11-11", 15, 30),
            workspaceId = space.id,
            createdBy = owner,
            seriesId = series.id,
            originalDate = "2026-11-11",
            isOverride = true,
        )
        // 他人创建的共享事件不应算作本人硬冲突
        val sharedLecture = FixedEvent(
            id = "shared-bob",
            title = "共享公开课",
            startsAt = at("2026-11-09", 10, 0),
            endsAt = at("2026-11-09", 11, 30),
            workspaceId = space.id,
            createdBy = "bob",
        )
        listOf(series, cancelledMonday, movedWednesday, sharedLecture).forEach(store::putEvent)

        // 1. 排在已取消课次 + 共享只读事件的时间段：无冲突直接保存，overlapConfirmed 为 false
        val freeOnCancelled = assertIs<WorkSessionSaveResult.Saved>(
            workSessionUseCases.create(
                WorkSessionDraft(
                    id = "ws-free-cancel",
                    taskId = task.id,
                    startsAt = at("2026-11-09", 10, 0),
                    endsAt = at("2026-11-09", 11, 30),
                ),
            ),
        ).session
        assertFalse(freeOnCancelled.overlapConfirmed)

        // 2. 排在已改期课次的原上午时段：无冲突直接保存
        val freeOnOriginalWed = assertIs<WorkSessionSaveResult.Saved>(
            workSessionUseCases.create(
                WorkSessionDraft(
                    id = "ws-free-wed-am",
                    taskId = task.id,
                    startsAt = at("2026-11-11", 10, 0),
                    endsAt = at("2026-11-11", 11, 30),
                ),
            ),
        ).session
        assertFalse(freeOnOriginalWed.overlapConfirmed)

        // 3. 排在改期后的下午时段 + 另一条跨周常规重复课次（11-16 周一 10:30-12:00，同时超过 11-11 日期截止）
        store.putWorkSession(
            WorkSession(
                id = "ws-existing",
                taskId = otherTask.id,
                startsAt = at("2026-11-16", 11, 0),
                endsAt = at("2026-11-16", 12, 0),
                ownerUserId = owner,
            ),
        )

        val pending = assertIs<WorkSessionSaveResult.NeedsConfirmation>(
            workSessionUseCases.create(
                listOf(
                    WorkSessionDraft(
                        id = "ws-hit-moved",
                        taskId = otherTask.id,
                        startsAt = at("2026-11-11", 14, 30),
                        endsAt = at("2026-11-11", 16, 0),
                    ),
                    WorkSessionDraft(
                        id = "ws-hit-next-week",
                        taskId = task.id,
                        startsAt = at("2026-11-16", 10, 30),
                        endsAt = at("2026-11-16", 12, 0),
                    ),
                ),
            ),
        ).confirmation

        assertEquals(2, pending.fixedEventConflicts.size)
        assertEquals("${series.id}@2026-11-11", pending.fixedEventConflicts[0].key)
        assertEquals("操作系统实验答疑", pending.fixedEventConflicts[0].title)
        assertTrue(pending.fixedEventConflicts[0].isRecurringOccurrence)
        assertEquals("${series.id}@2026-11-16", pending.fixedEventConflicts[1].key)

        assertEquals(1, pending.workSessionConflicts.size)
        assertEquals("数据库报告", pending.workSessionConflicts.single().title)

        assertEquals(listOf("ws-hit-next-week"), pending.pastDeadlineIds)
        assertEquals(TaskDeadline.DateOnly("2026-11-11"), pending.deadlineWarnings.single().deadline)

        // 任务截止变化会使旧 confirmationKey 失效
        store.putTask(task.withDeadline(TaskDeadline.DateOnly("2026-11-12")))
        val refreshedConfirmation = assertIs<WorkSessionSaveResult.NeedsConfirmation>(
            workSessionUseCases.create(
                listOf(
                    WorkSessionDraft(
                        id = "ws-hit-moved",
                        taskId = otherTask.id,
                        startsAt = at("2026-11-11", 14, 30),
                        endsAt = at("2026-11-11", 16, 0),
                    ),
                    WorkSessionDraft(
                        id = "ws-hit-next-week",
                        taskId = task.id,
                        startsAt = at("2026-11-16", 10, 30),
                        endsAt = at("2026-11-16", 12, 0),
                    ),
                ),
                confirmed = pending,
            ),
        ).confirmation
        assertNotEquals(pending.confirmationKey, refreshedConfirmation.confirmationKey)

        val savedBatch = assertIs<WorkSessionSaveResult.Saved>(
            workSessionUseCases.create(
                listOf(
                    WorkSessionDraft(
                        id = "ws-hit-moved",
                        taskId = otherTask.id,
                        startsAt = at("2026-11-11", 14, 30),
                        endsAt = at("2026-11-11", 16, 0),
                    ),
                    WorkSessionDraft(
                        id = "ws-hit-next-week",
                        taskId = task.id,
                        startsAt = at("2026-11-16", 10, 30),
                        endsAt = at("2026-11-16", 12, 0),
                    ),
                ),
                confirmed = refreshedConfirmation,
            ),
        ).sessions
        assertTrue(savedBatch.all { it.overlapConfirmed })
    }

    @Test
    fun updateRescheduleFocusLifecycleAndDetachedCapabilitiesFollowInvariants() = runTest {
        val task = Task(id = "t-focus", title = "专注编码", createdBy = owner)
        store.putTask(task)

        val initial = WorkSession(
            id = "ws-stage",
            taskId = task.id,
            startsAt = at("2026-11-10", 9, 0),
            endsAt = at("2026-11-10", 10, 0),
            ownerUserId = owner,
            stageId = "stage-alpha",
            note = "初稿",
        )
        store.putWorkSession(initial)

        // 拖拽改期保留 stageId 与说明
        val rescheduled = assertIs<WorkSessionSaveResult.Saved>(
            workSessionUseCases.reschedule(
                sessionId = initial.id,
                reschedule = WorkSessionReschedule(
                    startsAt = at("2026-11-10", 10, 0),
                    endsAt = at("2026-11-10", 11, 0),
                ),
                expectedOriginal = initial,
            ),
        ).session
        assertEquals("stage-alpha", rescheduled.stageId)
        assertEquals("初稿", rescheduled.note)

        // 启动专注计时自动懒补写 3600 秒目标；若后续步骤失败则整体回滚
        store.failNextFocusSessionWrite = true
        assertFailsWith<IllegalStateException> {
            focusUseCases.start(rescheduled.id, startedAt = at("2026-11-10", 10, 5), sessionId = "f-abort")
        }
        assertNull(workSessionUseCases.findById(rescheduled.id)?.focusTargetSeconds)

        val focus = focusUseCases.start(rescheduled.id, startedAt = at("2026-11-10", 10, 5), sessionId = "f-1")
        assertEquals(3_600L, workSessionUseCases.findById(rescheduled.id)?.focusTargetSeconds)

        // 运行中会话导致 canStartFocus = false，且已有计时记录后 canEditFocusTarget = false
        val runningDetail = assertNotNull(workSessionUseCases.getDetail(rescheduled.id))
        assertTrue(runningDetail.canEdit)
        assertFalse(runningDetail.canEditFocusTarget)
        assertFalse(runningDetail.canStartFocus)

        focusUseCases.pause(
            focus.id,
            FocusSegment("seg-1", focus.id, "c-1", at("2026-11-10", 10, 5), at("2026-11-10", 10, 25), FocusMode.FOREGROUND),
        )
        focusUseCases.resume(focus.id)
        focusUseCases.finish(
            focus.id,
            endedAt = at("2026-11-10", 10, 40),
            feedback = "进展顺利",
            finalSegment = FocusSegment("seg-2", focus.id, "c-2", at("2026-11-10", 10, 30), at("2026-11-10", 10, 40), FocusMode.BACKGROUND),
        )

        // 计时结束后不可修改 focusTargetSeconds，但仍允许编辑时间与说明
        assertFailsWith<IllegalArgumentException> {
            workSessionUseCases.update(
                sessionId = rescheduled.id,
                draft = WorkSessionEditDraft(
                    startsAt = at("2026-11-10", 13, 0),
                    endsAt = at("2026-11-10", 14, 0),
                    focusTargetSeconds = 1_800L,
                ),
            )
        }
        val editedAfterFocus = assertIs<WorkSessionSaveResult.Saved>(
            workSessionUseCases.update(
                sessionId = rescheduled.id,
                draft = WorkSessionEditDraft(
                    startsAt = at("2026-11-10", 13, 0),
                    endsAt = at("2026-11-10", 14, 0),
                    note = "下午继续",
                    focusTargetSeconds = 3_600L,
                ),
            ),
        ).session
        assertEquals("stage-alpha", editedAfterFocus.stageId)
        assertEquals("下午继续", editedAfterFocus.note)

        // 模拟任务删除后的脱链状态：不可编辑/改期/变更状态/开始计时，但可读取详情与历史时长并删除
        store.tasks.remove(task.id)
        store.putWorkSession(
            editedAfterFocus.copy(
                taskId = null,
                stageId = null,
                detachedAt = at("2026-11-10", 15, 0),
                detachReason = "TASK_DELETED",
            ),
        )
        val detachedDetail = assertNotNull(workSessionUseCases.getDetail(editedAfterFocus.id))
        assertTrue(detachedDetail.isDetached)
        assertFalse(detachedDetail.canEdit)
        assertFalse(detachedDetail.canReschedule)
        assertFalse(detachedDetail.canComplete)
        assertFalse(detachedDetail.canCancel)
        assertFalse(detachedDetail.canStartFocus)
        assertTrue(detachedDetail.canDelete)
        assertNull(detachedDetail.initialDraft)
        assertEquals(FocusDuration(20 * 60_000L, 10 * 60_000L), detachedDetail.focusDuration)

        assertFailsWith<IllegalArgumentException> {
            workSessionUseCases.reschedule(editedAfterFocus.id, at("2026-11-10", 16, 0), at("2026-11-10", 17, 0))
        }
        assertFailsWith<IllegalArgumentException> {
            focusUseCases.start(editedAfterFocus.id, startedAt = at("2026-11-10", 16, 0))
        }
        assertTrue(workSessionUseCases.delete(editedAfterFocus.id))
        assertNull(workSessionUseCases.getDetail(editedAfterFocus.id))
        assertEquals(30 * 60_000L, focusUseCases.getSummary(editedAfterFocus.id).duration.totalMillis)
    }

    @Test
    fun observeTaskWorkSessionsAndDetailReactImmediatelyToSessionAndFocusRevisions() = runTest {
        val t1 = Task(id = "t-obs-1", title = "论文精读", createdBy = owner)
        val t2 = Task(id = "t-obs-2", title = "实验复现", createdBy = owner)
        store.putTask(t1)
        store.putTask(t2)

        val taskIdFlow = MutableStateFlow(t1.id)
        val observedSnapshots = mutableListOf<Int>()
        val observedDurations = mutableListOf<Long>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            workSessionUseCases.queryByTask.observe(taskIdFlow).collect { snap ->
                observedSnapshots += snap.totalCount
                observedDurations += snap.totalFocusDuration.totalMillis
            }
        }

        assertEquals(listOf(0), observedSnapshots)

        val s1 = assertIs<WorkSessionSaveResult.Saved>(
            workSessionUseCases.create(
                WorkSessionDraft(id = "ws-o1", taskId = t1.id, startsAt = 0L, endsAt = 60_000L),
            ),
        ).session
        val s2 = assertIs<WorkSessionSaveResult.Saved>(
            workSessionUseCases.create(
                WorkSessionDraft(id = "ws-o2", taskId = t1.id, startsAt = 60_000L, endsAt = 120_000L),
            ),
        ).session
        assertEquals(listOf(0, 1, 2), observedSnapshots)

        val focus = focusUseCases.start(s1.id, startedAt = 0L, sessionId = "f-obs")
        focusUseCases.recordSegment(FocusSegment("seg-obs", focus.id, "c-obs", 0L, 25_000L, FocusMode.FOREGROUND))
        focusUseCases.finish(focus.id, endedAt = 25_000L)
        assertEquals(25_000L, observedDurations.last())

        workSessionUseCases.changeStatus(s1.id, WorkSessionStatus.COMPLETED)
        workSessionUseCases.changeStatus(s2.id, WorkSessionStatus.CANCELLED)

        val summary = workSessionUseCases.queryByTask(t1.id)
        assertEquals(0, summary.plannedCount)
        assertEquals(1, summary.completedCount)
        assertEquals(1, summary.cancelledCount)
        // 已取消时段不计入有效计划总时长，但历史专注时长完整保留
        assertEquals(60_000L, summary.totalPlannedMillis)
        assertEquals(25_000L, summary.totalFocusDuration.totalMillis)

        // 切换到另一任务自动发射该任务快照
        taskIdFlow.value = t2.id
        assertEquals(0, observedSnapshots.last())

        job.cancel()
    }
}
