package com.kaixu.domain.usecase.tasks

import androidx.room.execSQL
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteException
import com.kaixu.core.platform.LocalZone
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.database.openDesktopDatabase
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.data.repository.DefaultTaskRepository
import com.kaixu.domain.model.scheduling.FocusSession
import com.kaixu.domain.model.scheduling.FocusSessionStatus
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.model.tasks.CompleteTaskResult
import com.kaixu.domain.model.tasks.FutureSessionPolicy
import com.kaixu.domain.model.tasks.TaskDeadline
import com.kaixu.domain.model.tasks.TaskDraft
import com.kaixu.domain.model.tasks.TaskPriority
import com.kaixu.domain.model.tasks.TaskQuery
import com.kaixu.domain.model.tasks.TaskScheduleState
import com.kaixu.domain.model.tasks.TaskStatus
import com.kaixu.domain.model.workspaces.WorkspaceKind
import com.kaixu.domain.repository.LocalDataRevisionTracker
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

class TaskUseCasesDatabaseTest {
    private val shanghai = LocalZone.of("Asia/Shanghai")
    private val now = shanghai.startOfDayMillis("2026-11-25") + 15L * 3_600_000L

    @Test
    fun endToEndCreateEditQueryCompleteReopenAndDeleteOnRoom() = runTest {
        val directory = Files.createTempDirectory("kaixu-task-usecases").toFile()
        val file = directory.resolve("tasks-usecases.db")
        try {
            val db = openDesktopDatabase(file)
            try {
                val sources = LocalDataSources(db)
                val revisionTracker = LocalDataRevisionTracker()
                val repo = DefaultTaskRepository(
                    source = sources.tasks,
                    workspaces = sources.workspaces,
                    workSessions = sources.workSessions,
                    ownerUserId = LOCAL_ACTOR_ID,
                    transaction = sources.transaction,
                    revisionTracker = revisionTracker,
                )
                val useCases = TaskUseCases(repo, defaultZone = shanghai)

                val workspace = sources.workspaces.createWorkspace(WorkspaceKind.COURSE, "系统分析与设计")
                val created = useCases.createFromDraft(
                    TaskDraft(
                        title = "  编写用例层  ",
                        description = "  完成领域层重构  ",
                        workspaceId = workspace.id,
                        priority = TaskPriority.HIGH,
                        deadline = TaskDeadline.DateOnly("2026-11-26"),
                        estimatedMinutes = 120,
                    ),
                )
                val edited = useCases.updateFromDraft(
                    taskId = created.id,
                    draft = TaskDraft(
                        title = "完善任务用例",
                        description = "补充完整事务测试",
                        workspaceId = workspace.id,
                        priority = TaskPriority.NORMAL,
                        deadline = TaskDeadline.DateOnly("2026-11-24"),
                        estimatedMinutes = 90,
                        blockedNote = "待验收",
                    ),
                    nextStatus = TaskStatus.IN_PROGRESS,
                )
                assertEquals(TaskStatus.IN_PROGRESS, edited.status)

                val pastDone = sources.workSessions.createWorkSession(
                    WorkSession("ws-past-done", edited.id, now - 7_200_000L, now - 3_600_000L, ownerUserId = LOCAL_ACTOR_ID, status = WorkSessionStatus.COMPLETED),
                )
                val pastPlan = sources.workSessions.createWorkSession(
                    WorkSession("ws-past-plan", edited.id, now - 1_800_000L, now - 600_000L, ownerUserId = LOCAL_ACTOR_ID, status = WorkSessionStatus.PLANNED),
                )
                val futurePlan = sources.workSessions.createWorkSession(
                    WorkSession("ws-future-plan", edited.id, now + 3_600_000L, now + 7_200_000L, ownerUserId = LOCAL_ACTOR_ID, status = WorkSessionStatus.PLANNED),
                )
                val foreignPlan = sources.workSessions.createWorkSession(
                    WorkSession("ws-foreign", edited.id, now + 3_600_000L, now + 7_200_000L, ownerUserId = "other-user", status = WorkSessionStatus.PLANNED),
                )
                sources.focus.createFocusSession(
                    FocusSession("focus-active", futurePlan.id, now + 3_600_000L, ownerUserId = LOCAL_ACTOR_ID),
                )

                val listSnapshot = useCases.queryTasks(TaskQuery(), nowMillis = now)
                val item = listSnapshot.items.single()
                assertEquals(TaskScheduleState.HAS_SESSIONS, item.scheduleState)
                assertEquals(3, item.activeSessionCount)
                assertTrue(item.isOverdue)

                val detailBefore = assertNotNull(useCases.getDetail(edited.id, nowMillis = now))
                assertEquals(listOf(futurePlan.id), detailBefore.futurePlannedSessions.map { it.id })

                // 未指定未来时段策略时返回确认快照，数据库保持未完成
                val pending = assertIs<CompleteTaskResult.NeedsFutureSessionDecision>(
                    useCases.complete(edited.id, nowMillis = now, policy = null),
                )
                assertEquals(listOf(futurePlan.id), pending.futureSessions.map { it.id })
                assertEquals(TaskStatus.IN_PROGRESS, sources.tasks.findTaskById(edited.id)?.status)

                // 确认取消未来时段：同一事务内完成任务、取消未来计划时段并中断活动计时
                val completed = assertIs<CompleteTaskResult.Completed>(
                    useCases.complete(edited.id, nowMillis = now, policy = FutureSessionPolicy.CANCEL_FUTURE),
                )
                assertEquals(TaskStatus.COMPLETED, completed.task.status)
                assertEquals(1, completed.cancelledFutureCount)
                assertEquals(TaskStatus.COMPLETED, sources.tasks.findTaskById(edited.id)?.status)
                assertEquals(WorkSessionStatus.CANCELLED, sources.workSessions.findWorkSessionById(futurePlan.id)?.status)
                assertEquals(FocusSessionStatus.INTERRUPTED, sources.focus.findFocusSessionById("focus-active")?.status)
                assertEquals(WorkSessionStatus.COMPLETED, sources.workSessions.findWorkSessionById(pastDone.id)?.status)
                assertEquals(WorkSessionStatus.PLANNED, sources.workSessions.findWorkSessionById(pastPlan.id)?.status)
                assertEquals(WorkSessionStatus.PLANNED, sources.workSessions.findWorkSessionById(foreignPlan.id)?.status)

                // 恢复未完成：不重建已取消时段，历史时段仍计入 HAS_SESSIONS
                val reopened = useCases.reopen(edited.id, TaskStatus.IN_PROGRESS)
                assertEquals(TaskStatus.IN_PROGRESS, reopened.status)
                assertNull(reopened.completedAt)
                assertEquals(WorkSessionStatus.CANCELLED, sources.workSessions.findWorkSessionById(futurePlan.id)?.status)
                assertEquals(2, useCases.queryTasks(TaskQuery(), nowMillis = now).items.single().activeSessionCount)

                // 归档工作区后拒绝所有写操作并标记只读
                assertTrue(sources.workspaces.archiveWorkspace(workspace.id))
                val archivedItem = useCases.queryTasks(TaskQuery(), nowMillis = now).items.single()
                assertTrue(archivedItem.workspaceArchived)
                assertFalse(archivedItem.canEdit)
                assertFalse(assertNotNull(useCases.getDetail(edited.id, nowMillis = now)).canEdit)
                assertFailsWith<IllegalArgumentException> {
                    useCases.updateFromDraft(edited.id, reopened.copy(title = "越权").let {
                        TaskDraft(title = it.title, workspaceId = it.workspaceId)
                    })
                }
                assertFailsWith<IllegalArgumentException> { useCases.complete(edited.id, nowMillis = now) }
                assertFailsWith<IllegalArgumentException> { useCases.reopen(edited.id) }
                assertFailsWith<IllegalArgumentException> { useCases.delete(edited.id) }

                // 恢复工作区后删除任务：原子脱链全部关联时段
                assertTrue(sources.workspaces.restoreWorkspace(workspace.id))
                assertTrue(useCases.delete(edited.id))
                assertNull(sources.tasks.findTaskById(edited.id))
                assertTrue(useCases.queryTasks(TaskQuery(), nowMillis = now).items.isEmpty())
                val detached = assertNotNull(sources.workSessions.findWorkSessionById(pastDone.id))
                assertNull(detached.taskId)
                assertEquals("TASK_DELETED", detached.detachReason)
            } finally {
                db.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun completeTaskRollsBackTaskWhenFutureSessionCancelAbortsInSqlite() = runTest {
        val directory = Files.createTempDirectory("kaixu-task-complete-rollback").toFile()
        val file = directory.resolve("rollback.db")
        try {
            val db = openDesktopDatabase(file)
            try {
                val sources = LocalDataSources(db)
                val revisionTracker = LocalDataRevisionTracker()
                val repo = DefaultTaskRepository(
                    source = sources.tasks,
                    workspaces = sources.workspaces,
                    workSessions = sources.workSessions,
                    ownerUserId = LOCAL_ACTOR_ID,
                    transaction = sources.transaction,
                    revisionTracker = revisionTracker,
                )
                val useCases = TaskUseCases(repo, defaultZone = shanghai)

                val task = useCases.createFromDraft(TaskDraft(title = "原子完成测试"))
                val futurePlan = sources.workSessions.createWorkSession(
                    WorkSession("ws-future", task.id, now + 3_600_000L, now + 7_200_000L, ownerUserId = LOCAL_ACTOR_ID),
                )
                sources.focus.createFocusSession(
                    FocusSession("focus-1", futurePlan.id, now + 3_600_000L, ownerUserId = LOCAL_ACTOR_ID),
                )
                val revisionBeforeAbort = useCases.revision.value

                db.useWriterConnection { conn ->
                    conn.execSQL(
                        "CREATE TRIGGER abort_cancel BEFORE UPDATE ON work_sessions BEGIN SELECT RAISE(ABORT, 'simulated failure'); END",
                    )
                }
                val rejected = assertFailsWith<SQLiteException> {
                    useCases.complete(task.id, nowMillis = now, policy = FutureSessionPolicy.CANCEL_FUTURE)
                }
                assertContains(rejected.message.orEmpty(), "simulated failure")
                assertEquals(TaskStatus.TODO, sources.tasks.findTaskById(task.id)?.status)
                assertNull(sources.tasks.findTaskById(task.id)?.completedAt)
                assertEquals(WorkSessionStatus.PLANNED, sources.workSessions.findWorkSessionById(futurePlan.id)?.status)
                assertEquals(FocusSessionStatus.ACTIVE, sources.focus.findFocusSessionById("focus-1")?.status)
                assertEquals(TaskStatus.TODO, repo.tasks.value.single().status)
                assertEquals(revisionBeforeAbort, useCases.revision.value)
            } finally {
                db.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
