package com.kaixu.domain.usecase.scheduling

import com.kaixu.core.platform.LocalZone
import com.kaixu.domain.model.scheduling.CalendarBlockKind
import com.kaixu.domain.model.scheduling.CalendarQuery
import com.kaixu.domain.model.scheduling.CalendarViewMode
import com.kaixu.domain.model.scheduling.CalendarWorkspaceFilter
import com.kaixu.domain.model.scheduling.EditScope
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.FixedEventDraft
import com.kaixu.domain.model.scheduling.FixedEventTarget
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.tasks.TaskDeadline
import com.kaixu.domain.model.tasks.TaskPriority
import com.kaixu.domain.model.tasks.TaskStatus
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceKind
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.repository.LocalDataRevisionTracker
import com.kaixu.testing.TestFixedEventRepository
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FixedEventDomainUseCasesTest {
    private val owner = "alice"
    private val shanghai = LocalZone.of("Asia/Shanghai")
    private val revisionTracker = LocalDataRevisionTracker()
    private val repository = TestFixedEventRepository(ownerUserId = owner, revisionTracker = revisionTracker)
    private val useCases = FixedEventUseCases(repository, defaultZone = shanghai)

    private fun at(date: String, hour: Int, minute: Int = 0): Long =
        shanghai.atLocalDateTimeMillis(date, (hour * 60L + minute) * 60_000L)

    private fun workspace(
        id: String,
        ownerId: String = owner,
        status: WorkspaceStatus = WorkspaceStatus.ACTIVE,
        kind: WorkspaceKind = WorkspaceKind.COURSE,
    ) = Workspace(
        id = id,
        ownerUserId = ownerId,
        kind = kind,
        name = "空间-$id",
        description = null,
        linkedCourseId = null,
        status = status,
        createdAt = 0L,
        updatedAt = 0L,
        archivedAt = if (status == WorkspaceStatus.ARCHIVED) 1L else null,
    )

    @Test
    fun getDetailResolvesSingleOccurrenceModifiedOverrideSharedAndArchivedPermissions() = runTest {
        val activeSpace = workspace("w-active")
        val archivedSpace = workspace("w-arch", status = WorkspaceStatus.ARCHIVED)
        val foreignSpace = workspace("w-foreign", ownerId = "bob")
        repository.putWorkspace(activeSpace)
        repository.putWorkspace(archivedSpace)
        repository.putWorkspace(foreignSpace)

        val single = FixedEvent(
            id = "e-single",
            title = "单次研讨",
            startsAt = at("2026-11-02", 10),
            endsAt = at("2026-11-02", 11),
            workspaceId = activeSpace.id,
            createdBy = owner,
        )
        val series = FixedEvent(
            id = "e-series",
            title = "每周例会",
            startsAt = at("2026-11-02", 14),
            endsAt = at("2026-11-02", 15),
            workspaceId = activeSpace.id,
            createdBy = owner,
            repeatWeekdays = setOf(1, 3),
            repeatUntil = "2026-11-18",
        )
        val override = FixedEvent(
            id = "e-override",
            title = "例会改期",
            startsAt = at("2026-11-04", 16),
            endsAt = at("2026-11-04", 17),
            workspaceId = activeSpace.id,
            createdBy = owner,
            seriesId = series.id,
            originalDate = "2026-11-04",
            isOverride = true,
        )
        val cancelled = FixedEvent(
            id = "e-cancelled",
            title = "已取消例会",
            startsAt = at("2026-11-09", 14),
            endsAt = at("2026-11-09", 15),
            createdBy = owner,
            seriesId = series.id,
            originalDate = "2026-11-09",
            isOverride = true,
            isCancelled = true,
        )
        val shared = FixedEvent(
            id = "e-shared",
            title = "共享讲座",
            startsAt = at("2026-11-03", 9),
            endsAt = at("2026-11-03", 10),
            workspaceId = activeSpace.id,
            createdBy = "bob",
        )
        val hiddenForeign = FixedEvent(
            id = "e-foreign",
            title = "他人私密",
            startsAt = at("2026-11-03", 11),
            endsAt = at("2026-11-03", 12),
            workspaceId = foreignSpace.id,
            createdBy = "bob",
        )
        val archivedEvent = FixedEvent(
            id = "e-archived",
            title = "归档事件",
            startsAt = at("2026-11-05", 9),
            endsAt = at("2026-11-05", 10),
            workspaceId = archivedSpace.id,
            createdBy = owner,
        )
        listOf(single, series, override, cancelled, shared, hiddenForeign, archivedEvent).forEach(repository::putEvent)

        val singleDetail = assertNotNull(useCases.getDetail(FixedEventTarget.SingleOrSeries(single.id)))
        assertTrue(singleDetail.canEdit)
        assertFalse(singleDetail.supportsScopeSelection)

        val generatedOccDetail = assertNotNull(useCases.getDetail(FixedEventTarget.Occurrence(series.id, "2026-11-11")))
        assertTrue(generatedOccDetail.canEdit)
        assertTrue(generatedOccDetail.supportsScopeSelection)
        assertEquals(at("2026-11-11", 14), generatedOccDetail.occurrenceStart)
        assertEquals(setOf(1, 3), generatedOccDetail.initialDraft.repeatWeekdays)

        // 通过例外行 ID 与 Occurrence 双路径都能还原父序列作用域编辑能力
        val overrideById = assertNotNull(useCases.getDetail(FixedEventTarget.SingleOrSeries(override.id)))
        val overrideByOcc = assertNotNull(useCases.getDetail(FixedEventTarget.Occurrence(series.id, "2026-11-04")))
        assertEquals(overrideByOcc, overrideById)
        assertEquals("例会改期", overrideById.record.title)
        assertTrue(overrideById.supportsScopeSelection)
        assertEquals("2026-11-18", overrideById.initialDraft.repeatUntil)

        assertNull(useCases.getDetail(FixedEventTarget.Occurrence(series.id, "2026-11-09")))
        assertNull(useCases.getDetail(FixedEventTarget.SingleOrSeries(hiddenForeign.id)))

        val sharedDetail = assertNotNull(useCases.getDetail(FixedEventTarget.SingleOrSeries(shared.id)))
        assertTrue(sharedDetail.isSharedReadOnly)
        assertFalse(sharedDetail.canEdit)
        assertFalse(sharedDetail.canDelete)

        val archivedDetail = assertNotNull(useCases.getDetail(FixedEventTarget.SingleOrSeries(archivedEvent.id)))
        assertTrue(archivedDetail.workspaceArchived)
        assertFalse(archivedDetail.canEdit)
    }

    @Test
    fun createUpdateAndDeleteEnforceWorkspaceConflictsRealTaskTitlesAndScopes() = runTest {
        val activeSpace = workspace("w-active")
        val archivedSpace = workspace("w-arch", status = WorkspaceStatus.ARCHIVED)
        val foreignSpace = workspace("w-foreign", ownerId = "bob")
        repository.putWorkspace(activeSpace)
        repository.putWorkspace(archivedSpace)
        repository.putWorkspace(foreignSpace)

        val linkedTask = Task(id = "t-review", title = "完成架构设计文档", createdBy = owner, workspaceId = activeSpace.id)
        repository.putTask(linkedTask)
        repository.putWorkSession(
            WorkSession(
                id = "ws-linked",
                taskId = linkedTask.id,
                startsAt = at("2026-11-02", 9),
                endsAt = at("2026-11-02", 10),
                ownerUserId = owner,
            ),
        )
        repository.putWorkSession(
            WorkSession(
                id = "ws-detached",
                taskId = null,
                startsAt = at("2026-11-02", 15),
                endsAt = at("2026-11-02", 16),
                ownerUserId = owner,
                detachedAt = 1L,
                detachReason = "TASK_DELETED",
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            useCases.create(
                FixedEventDraft("归档测试", at("2026-11-02", 11), at("2026-11-02", 12), workspaceId = archivedSpace.id),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            useCases.create(
                FixedEventDraft("外部空间", at("2026-11-02", 11), at("2026-11-02", 12), workspaceId = foreignSpace.id),
            )
        }

        // 与关联任务的个人时段冲突时展示真实任务标题；变化后的确认被拒绝
        val draftWithConflict = FixedEventDraft(
            title = "周一例会",
            startsAt = at("2026-11-02", 9, 30),
            endsAt = at("2026-11-02", 10, 30),
            workspaceId = activeSpace.id,
            repeatWeekdays = setOf(1),
            repeatUntil = "2026-11-23",
        )
        val pending = assertIs<FixedEventSaveResult.NeedsConfirmation>(useCases.create(draftWithConflict))
        assertEquals(linkedTask.title, pending.confirmation.conflicts.single().title)
        assertEquals(ScheduleConflictKind.WORK_SESSION, pending.confirmation.conflicts.single().kind)

        assertIs<FixedEventSaveResult.NeedsConfirmation>(
            useCases.create(draftWithConflict.copy(title = "改名了"), pending.confirmation),
        )
        val series = assertIs<FixedEventSaveResult.Saved>(
            useCases.create(draftWithConflict, pending.confirmation),
        ).event

        // 脱链时段 fallback 为“我的工作时段”
        val detachedPending = assertIs<FixedEventSaveResult.NeedsConfirmation>(
            useCases.create(FixedEventDraft("临时会", at("2026-11-02", 15, 30), at("2026-11-02", 16, 30))),
        )
        assertEquals("我的工作时段", detachedPending.confirmation.conflicts.single().title)

        // THIS_OCCURRENCE 移动到同序列另一个周一上也会检出同序列另一课次的冲突，并可基于已改期例外再次编辑
        val overlapSibling = assertIs<FixedEventSaveResult.NeedsConfirmation>(
            useCases.update(
                target = FixedEventTarget.Occurrence(series.id, "2026-11-09"),
                draft = FixedEventDraft(
                    title = "合并例会",
                    startsAt = at("2026-11-16", 9, 30),
                    endsAt = at("2026-11-16", 10, 30),
                    workspaceId = activeSpace.id,
                    repeatWeekdays = setOf(1),
                    repeatUntil = "2026-11-23",
                ),
                scope = EditScope.THIS_OCCURRENCE,
            ),
        )
        assertEquals("${series.id}@2026-11-16", overlapSibling.confirmation.conflicts.single().id)

        val editedOcc = assertIs<FixedEventSaveResult.Saved>(
            useCases.update(
                target = FixedEventTarget.Occurrence(series.id, "2026-11-09"),
                draft = FixedEventDraft(
                    title = "单次调课",
                    startsAt = at("2026-11-10", 11),
                    endsAt = at("2026-11-10", 12),
                    workspaceId = activeSpace.id,
                    repeatWeekdays = setOf(1),
                    repeatUntil = "2026-11-23",
                ),
                scope = EditScope.THIS_OCCURRENCE,
            ),
        ).event
        assertNull(editedOcc.repeatWeekdays)
        val reEditedOcc = assertIs<FixedEventSaveResult.Saved>(
            useCases.update(
                target = FixedEventTarget.SingleOrSeries(editedOcc.id),
                draft = FixedEventDraft(
                    title = "单次调课（二次修订）",
                    startsAt = at("2026-11-10", 11),
                    endsAt = at("2026-11-10", 12),
                    workspaceId = activeSpace.id,
                ),
                scope = EditScope.THIS_OCCURRENCE,
            ),
        ).event
        assertEquals(editedOcc.id, reEditedOcc.id)

        // 从首次课次执行 THIS_AND_FUTURE 编辑：旧序列应被软删除，而不是留下 repeatUntil < startsAt 的坏记录
        val replacedSeries = assertIs<FixedEventSaveResult.Saved>(
            useCases.update(
                target = FixedEventTarget.Occurrence(series.id, "2026-11-02"),
                draft = FixedEventDraft(
                    title = "整段替换序列",
                    startsAt = at("2026-11-02", 19),
                    endsAt = at("2026-11-02", 20),
                    workspaceId = activeSpace.id,
                    repeatWeekdays = setOf(1),
                    repeatUntil = "2026-11-23",
                ),
                scope = EditScope.THIS_AND_FUTURE,
            ),
        ).event
        assertNull(repository.findById(series.id))

        // 删除“仅本次”幂等且不影响后续；删除“本次及后续”截断序列
        assertTrue(useCases.delete(FixedEventTarget.Occurrence(replacedSeries.id, "2026-11-09"), EditScope.THIS_OCCURRENCE))
        assertFalse(useCases.delete(FixedEventTarget.Occurrence(replacedSeries.id, "2026-11-09"), EditScope.THIS_OCCURRENCE))
        assertTrue(useCases.delete(FixedEventTarget.Occurrence(replacedSeries.id, "2026-11-16"), EditScope.THIS_AND_FUTURE))
        assertEquals(
            listOf("2026-11-02"),
            useCases.occurrences(at("2026-11-01", 0), at("2026-11-30", 0)).map { it.originalDate },
        )
    }

    @Test
    fun queryCalendarSupportsDayWeekWorkspaceFilterCrossMidnightSlicingPackingAndObserve() = runTest {
        val activeSpace = workspace("w-active")
        val archivedSpace = workspace("w-arch", status = WorkspaceStatus.ARCHIVED)
        val foreignSpace = workspace("w-foreign", ownerId = "bob")
        repository.putWorkspace(activeSpace)
        repository.putWorkspace(archivedSpace)
        repository.putWorkspace(foreignSpace)

        val dateTask = Task(
            id = "t-date",
            title = "交周报",
            workspaceId = activeSpace.id,
            createdBy = owner,
            priority = TaskPriority.HIGH,
            deadlineDate = "2026-11-24",
        )
        val completedTask = Task(
            id = "t-done",
            title = "已完成任务",
            workspaceId = activeSpace.id,
            createdBy = owner,
            status = TaskStatus.COMPLETED,
            completedAt = 1L,
            deadlineDate = "2026-11-24",
        )
        repository.putTask(dateTask)
        repository.putTask(completedTask)

        // 跨午夜重复事件（23:00 -> 次日 01:00）+ 同时段工作时段形成周二同列重叠分列
        val overnight = FixedEvent(
            id = "ev-night",
            title = "夜间运维",
            startsAt = at("2026-11-23", 23),
            endsAt = at("2026-11-24", 1),
            workspaceId = activeSpace.id,
            createdBy = owner,
            repeatWeekdays = setOf(1),
            repeatUntil = "2026-11-30",
        )
        val shared = FixedEvent(
            id = "ev-shared",
            title = "共享答疑",
            startsAt = at("2026-11-24", 10),
            endsAt = at("2026-11-24", 11),
            workspaceId = archivedSpace.id,
            createdBy = "bob",
        )
        val foreignHidden = FixedEvent(
            id = "ev-foreign",
            title = "无权共享",
            startsAt = at("2026-11-24", 12),
            endsAt = at("2026-11-24", 13),
            workspaceId = foreignSpace.id,
            createdBy = "bob",
        )
        repository.putEvent(overnight)
        repository.putEvent(shared)
        repository.putEvent(foreignHidden)

        repository.putWorkSession(
            WorkSession("ws-1", dateTask.id, at("2026-11-24", 0, 30), at("2026-11-24", 2), ownerUserId = owner),
        )
        repository.putWorkSession(
            WorkSession("ws-cancel", dateTask.id, at("2026-11-24", 14), at("2026-11-24", 15), ownerUserId = owner, status = WorkSessionStatus.CANCELLED),
        )

        val weekAll = useCases.queryCalendar(
            CalendarQuery(mode = CalendarViewMode.WEEK, anchorDate = "2026-11-25"),
            nowMillis = at("2026-11-25", 10),
        )
        assertEquals(7, weekAll.days.size)
        assertEquals("2026-11-23", weekAll.spec.startDate)
        assertFalse(weekAll.blocks.any { it.recordId == foreignHidden.id || it.recordId == "ws-cancel" })
        assertEquals(CalendarBlockKind.SHARED_EVENT, weekAll.blocks.first { it.recordId == shared.id }.kind)
        assertFalse(weekAll.blocks.first { it.recordId == shared.id }.canEdit)
        assertTrue(weekAll.blocks.first { it.recordId == shared.id }.workspaceArchived)

        // 周二切片验证：包含跨午夜下半段(columnCount=2) 与工作时段重叠分列 + 仅日期截止条(已逾期)
        val tuesday = weekAll.days.first { it.date == "2026-11-24" }
        val nightSliceTue = tuesday.slices.first { it.block.recordId == overnight.id }
        val sessionSliceTue = tuesday.slices.first { it.block.recordId == "ws-1" }
        assertTrue(nightSliceTue.continuesFromPreviousDay)
        assertFalse(nightSliceTue.continuesToNextDay)
        assertEquals(2, nightSliceTue.columnCount)
        assertEquals(2, sessionSliceTue.columnCount)
        assertTrue(nightSliceTue.columnIndex != sessionSliceTue.columnIndex)

        val deadlineItem = tuesday.deadlines.single()
        assertEquals(dateTask.id, deadlineItem.taskId)
        assertEquals(TaskDeadline.DateOnly("2026-11-24"), deadlineItem.deadline)
        assertTrue(deadlineItem.isOverdue)

        // 仅查周二单日视图 + 按工作区过滤
        val dayActive = useCases.queryCalendar(
            CalendarQuery(CalendarViewMode.DAY, "2026-11-24", CalendarWorkspaceFilter.Workspace(activeSpace.id)),
            nowMillis = at("2026-11-25", 10),
        )
        assertEquals(1, dayActive.days.size)
        assertFalse(dayActive.blocks.any { it.recordId == shared.id })
        assertEquals(setOf(overnight.id, "ws-1"), dayActive.blocks.map { it.recordId }.toSet())

        // 响应式 observe 在 revision 变更时自动产出最新快照
        val queryFlow = MutableStateFlow(CalendarQuery(CalendarViewMode.DAY, "2026-11-26"))
        val emitted = mutableListOf<Int>()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            useCases.queryCalendar.observe(queryFlow, clock = { at("2026-11-26", 8) }).collect {
                emitted += it.blocks.size
            }
        }
        assertIs<FixedEventSaveResult.Saved>(
            useCases.create(FixedEventDraft("新周四日程", at("2026-11-26", 14), at("2026-11-26", 15))),
        )
        collectJob.cancel()
        assertEquals(listOf(0, 1), emitted)
    }
}
