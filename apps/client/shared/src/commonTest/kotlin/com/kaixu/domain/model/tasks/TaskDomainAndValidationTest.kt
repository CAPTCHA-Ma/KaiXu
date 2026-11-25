package com.kaixu.domain.model.tasks

import com.kaixu.core.platform.LocalZone
import com.kaixu.domain.validation.tasks.TaskInput
import com.kaixu.domain.validation.tasks.TaskTitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskDomainAndValidationTest {

    @Test
    fun deadlineRejectsInvalidDatesAndRoundTripsOnTask() {
        for (bad in listOf("2026-02-29", "2026-2-01", "0000-01-01", "2026-04-31", "")) {
            assertFailsWith<IllegalArgumentException> { TaskDeadline.DateOnly(bad) }
        }
        val leapDate = TaskDeadline.DateOnly("2028-02-29")
        val exactTime = 1_800_000_000_000L

        val base = Task(id = "task-1", title = "任务")
        assertEquals(TaskDeadline.None, base.deadline)

        val withDate = base.withDeadline(leapDate)
        assertEquals("2028-02-29", withDate.deadlineDate)
        assertNull(withDate.deadlineAt)
        assertEquals(leapDate, withDate.deadline)

        val withExact = withDate.withDeadline(TaskDeadline.ExactTime(exactTime))
        assertNull(withExact.deadlineDate)
        assertEquals(exactTime, withExact.deadlineAt)
        assertEquals(TaskDeadline.ExactTime(exactTime), withExact.deadline)

        val cleared = withExact.withDeadline(TaskDeadline.None)
        assertNull(cleared.deadlineDate)
        assertNull(cleared.deadlineAt)
        assertEquals(TaskDeadline.None, cleared.deadline)

        assertFailsWith<IllegalArgumentException> {
            Task(id = "bad", title = "冲突", deadlineDate = "2026-11-25", deadlineAt = exactTime).deadline
        }
    }

    @Test
    fun overdueAndPastChecksRespectZoneDateBoundaryAndCompletionStatus() {
        // 默认跟随系统时区的边界检查（无论宿主位于 UTC 或任意时区均保持确定）
        val sysZone = LocalZone.systemDefault()
        val sysMidnight = sysZone.startOfDayMillis("2026-11-26")
        assertFalse(TaskDeadline.None.isPastAt(sysMidnight))
        assertFalse(Task(id = "no-ddl", title = "无截止").isOverdueAt(sysMidnight))

        val dateDeadline = TaskDeadline.DateOnly("2026-11-25")
        assertFalse(dateDeadline.isPastAt(sysMidnight - 1))
        assertTrue(dateDeadline.isPastAt(sysMidnight))

        // 显式注入不同目标时区验证跨时区判定
        val shanghaiZone = LocalZone.of("Asia/Shanghai")
        val utcZone = LocalZone.of("UTC")
        val shanghaiMidnight = shanghaiZone.startOfDayMillis("2026-11-26")
        val dateTask = Task(id = "date-task", title = "日期截止", deadlineDate = "2026-11-25")
        assertFalse(dateTask.isOverdueAt(shanghaiMidnight - 1, shanghaiZone))
        assertTrue(dateTask.isOverdueAt(shanghaiMidnight, shanghaiZone))
        assertFalse(dateTask.isOverdueAt(shanghaiMidnight, utcZone))

        val exactMillis = shanghaiZone.startOfDayMillis("2026-11-25") + 18L * 3_600_000L
        val exactDeadline = TaskDeadline.ExactTime(exactMillis)
        assertFalse(exactDeadline.isPastAt(exactMillis, shanghaiZone))
        assertTrue(exactDeadline.isPastAt(exactMillis + 1, shanghaiZone))

        val completedTask = Task(
            id = "done",
            title = "已完成",
            status = TaskStatus.COMPLETED,
            completedAt = exactMillis - 1000,
            deadlineAt = exactMillis,
        )
        assertTrue(completedTask.deadline.isPastAt(exactMillis + 1, shanghaiZone))
        assertFalse(completedTask.isOverdueAt(exactMillis + 1, shanghaiZone))
    }

    @Test
    fun sortKeyOrdersSameDayExactBeforeDateOnlyAndNoneLast() {
        val zone = LocalZone.of("Asia/Shanghai")
        val dayStart = zone.startOfDayMillis("2026-11-25")
        val sameDayNoon = TaskDeadline.ExactTime(dayStart + 12L * 3_600_000L)
        val sameDayLate = TaskDeadline.ExactTime(dayStart + 23L * 3_600_000L + 59L * 60_000L)
        val sameDayDateOnly = TaskDeadline.DateOnly("2026-11-25")
        val nextDayMidnight = TaskDeadline.ExactTime(zone.startOfDayMillis("2026-11-26"))
        val none = TaskDeadline.None

        val sorted = listOf(none, nextDayMidnight, sameDayDateOnly, sameDayLate, sameDayNoon)
            .sortedBy { it.sortKeyMillis(zone) }

        assertEquals(
            listOf(sameDayNoon, sameDayLate, sameDayDateOnly, nextDayMidnight, none),
            sorted,
        )
    }

    @Test
    fun workspaceAndDeadlineFiltersMatchExpectedBoundaries() {
        assertFailsWith<IllegalArgumentException> { TaskWorkspaceFilter.Workspace(" ") }
        assertFailsWith<IllegalArgumentException> { TaskDeadlineFilter.OnDate("2026-02-29") }

        assertTrue(TaskWorkspaceFilter.All.matches(null))
        assertTrue(TaskWorkspaceFilter.All.matches("ws-1"))
        assertTrue(TaskWorkspaceFilter.Standalone.matches(null))
        assertFalse(TaskWorkspaceFilter.Standalone.matches("ws-1"))
        assertTrue(TaskWorkspaceFilter.Workspace("ws-1").matches("ws-1"))
        assertFalse(TaskWorkspaceFilter.Workspace("ws-1").matches("ws-2"))
        assertFalse(TaskWorkspaceFilter.Workspace("ws-1").matches(null))

        val zone = LocalZone.of("America/New_York")
        val now = zone.startOfDayMillis("2026-11-25") + 15L * 3_600_000L
        val noDeadlineTask = Task(id = "t0", title = "无截止")
        val yesterdayDateTask = Task(id = "t1", title = "昨日截止", deadlineDate = "2026-11-24")
        val todayDateTask = Task(id = "t2", title = "今日日期", deadlineDate = "2026-11-25")
        val todayPastExactTask = Task(
            id = "t3",
            title = "今日已过时刻",
            deadlineAt = now - 60_000L,
        )
        val todayFutureExactTask = Task(
            id = "t4",
            title = "今日稍后时刻",
            deadlineAt = now,
        )
        val day6DateTask = Task(id = "t5", title = "第7天内日期", deadlineDate = "2026-12-01")
        val day7DateTask = Task(id = "t6", title = "第8天日期", deadlineDate = "2026-12-02")
        val day6EndExactTask = Task(
            id = "t7",
            title = "第7天末时刻",
            deadlineAt = zone.startOfDayMillis("2026-12-02") - 1,
        )
        val day7StartExactTask = Task(
            id = "t8",
            title = "第8天零点时刻",
            deadlineAt = zone.startOfDayMillis("2026-12-02"),
        )

        assertTrue(TaskDeadlineFilter.All.matches(noDeadlineTask, now, zone))
        assertTrue(TaskDeadlineFilter.NoDeadline.matches(noDeadlineTask, now, zone))
        assertFalse(TaskDeadlineFilter.NoDeadline.matches(todayDateTask, now, zone))

        assertFalse(TaskDeadlineFilter.Overdue.matches(noDeadlineTask, now, zone))
        assertTrue(TaskDeadlineFilter.Overdue.matches(yesterdayDateTask, now, zone))
        assertFalse(TaskDeadlineFilter.Overdue.matches(todayDateTask, now, zone))
        assertTrue(TaskDeadlineFilter.Overdue.matches(todayPastExactTask, now, zone))
        assertFalse(TaskDeadlineFilter.Overdue.matches(todayFutureExactTask, now, zone))

        assertTrue(TaskDeadlineFilter.Today.matches(todayDateTask, now, zone))
        assertTrue(TaskDeadlineFilter.Today.matches(todayPastExactTask, now, zone))
        assertTrue(TaskDeadlineFilter.Today.matches(todayFutureExactTask, now, zone))
        assertFalse(TaskDeadlineFilter.Today.matches(yesterdayDateTask, now, zone))

        assertTrue(TaskDeadlineFilter.OnDate("2026-12-01").matches(day6DateTask, now, zone))
        assertFalse(TaskDeadlineFilter.OnDate("2026-12-01").matches(day7DateTask, now, zone))

        assertFalse(TaskDeadlineFilter.UpcomingSevenDays.matches(noDeadlineTask, now, zone))
        assertFalse(TaskDeadlineFilter.UpcomingSevenDays.matches(yesterdayDateTask, now, zone))
        assertTrue(TaskDeadlineFilter.UpcomingSevenDays.matches(todayDateTask, now, zone))
        assertFalse(TaskDeadlineFilter.UpcomingSevenDays.matches(todayPastExactTask, now, zone))
        assertTrue(TaskDeadlineFilter.UpcomingSevenDays.matches(todayFutureExactTask, now, zone))
        assertTrue(TaskDeadlineFilter.UpcomingSevenDays.matches(day6DateTask, now, zone))
        assertFalse(TaskDeadlineFilter.UpcomingSevenDays.matches(day7DateTask, now, zone))
        assertTrue(TaskDeadlineFilter.UpcomingSevenDays.matches(day6EndExactTask, now, zone))
        assertFalse(TaskDeadlineFilter.UpcomingSevenDays.matches(day7StartExactTask, now, zone))
    }

    @Test
    fun draftValidationToNewTaskAndApplyDraftEnforceRules() {
        assertEquals("请输入任务标题", TaskInput.draftErrorOrNull(TaskDraft(title = "  ")))
        assertEquals(
            "标题最多 ${TaskTitle.MAX_LENGTH} 个字符",
            TaskInput.draftErrorOrNull(TaskDraft(title = "字".repeat(121))),
        )
        assertEquals(
            "工作区 ID 不能为空白",
            TaskInput.draftErrorOrNull(TaskDraft(title = "有效", workspaceId = " ")),
        )
        assertEquals(
            "预计时长不能为负数",
            TaskInput.draftErrorOrNull(TaskDraft(title = "有效", estimatedMinutes = -1)),
        )
        assertNull(TaskInput.draftErrorOrNull(TaskDraft(title = "有效", estimatedMinutes = 0)))

        val created = TaskInput.toNewTask(
            draft = TaskDraft(
                title = "  新任务  ",
                description = "   ",
                workspaceId = "ws-1",
                priority = TaskPriority.HIGH,
                deadline = TaskDeadline.DateOnly("2026-11-30"),
                estimatedMinutes = 45,
                blockedNote = "  等待接口  ",
            ),
            createdBy = "user-1",
            id = "task-custom",
        )
        assertEquals("task-custom", created.id)
        assertEquals("新任务", created.title)
        assertNull(created.description)
        assertEquals("ws-1", created.workspaceId)
        assertEquals("user-1", created.createdBy)
        assertEquals(TaskStatus.TODO, created.status)
        assertEquals(TaskPriority.HIGH, created.priority)
        assertEquals("2026-11-30", created.deadlineDate)
        assertNull(created.deadlineAt)
        assertEquals(45, created.estimatedMinutes)
        assertEquals("等待接口", created.blockedNote)

        assertEquals(created.toDraft(), TaskInput.normalizedDraft(created.toDraft()))

        assertFailsWith<IllegalArgumentException> {
            TaskInput.toNewTask(TaskDraft(title = "标题"), createdBy = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            TaskInput.toNewTask(TaskDraft(title = "标题"), createdBy = "user-1", id = " ")
        }

        val withSource = created.copy(
            assigneeUserId = "assignee-1",
            sourceExcerpt = "群通知原文",
            sourceUrl = "https://example.com",
        )
        val exactDeadline = 1_900_000_000_000L
        val edited = TaskInput.applyDraft(
            existing = withSource,
            draft = TaskDraft(
                title = "  已更新任务  ",
                description = "  详细说明  ",
                workspaceId = null,
                priority = TaskPriority.LOW,
                deadline = TaskDeadline.ExactTime(exactDeadline),
                estimatedMinutes = 30,
                blockedNote = "  ",
            ),
            nextStatus = TaskStatus.IN_PROGRESS,
        )
        assertEquals(withSource.id, edited.id)
        assertEquals(withSource.createdBy, edited.createdBy)
        assertEquals("assignee-1", edited.assigneeUserId)
        assertEquals("群通知原文", edited.sourceExcerpt)
        assertEquals("https://example.com", edited.sourceUrl)
        assertEquals("已更新任务", edited.title)
        assertEquals("详细说明", edited.description)
        assertNull(edited.workspaceId)
        assertEquals(TaskPriority.LOW, edited.priority)
        assertNull(edited.deadlineDate)
        assertEquals(exactDeadline, edited.deadlineAt)
        assertEquals(30, edited.estimatedMinutes)
        assertNull(edited.blockedNote)
        assertEquals(TaskStatus.IN_PROGRESS, edited.status)

        assertFailsWith<IllegalArgumentException> {
            TaskInput.applyDraft(edited, edited.toDraft(), nextStatus = TaskStatus.COMPLETED)
        }

        val completed = edited.copy(status = TaskStatus.COMPLETED, completedAt = 1_900_000_100_000L)
        val editedCompleted = TaskInput.applyDraft(
            existing = completed,
            draft = completed.toDraft().copy(title = " 已完成补充 ", description = " 复盘备注 "),
            nextStatus = TaskStatus.COMPLETED,
        )
        assertEquals("已完成补充", editedCompleted.title)
        assertEquals("复盘备注", editedCompleted.description)
        assertEquals(1_900_000_100_000L, editedCompleted.completedAt)

        assertFailsWith<IllegalArgumentException> {
            TaskInput.applyDraft(completed, completed.toDraft(), nextStatus = TaskStatus.TODO)
        }
        assertFailsWith<IllegalArgumentException> {
            TaskInput.applyDraft(completed, completed.toDraft(), nextStatus = TaskStatus.IN_PROGRESS)
        }
    }
}
