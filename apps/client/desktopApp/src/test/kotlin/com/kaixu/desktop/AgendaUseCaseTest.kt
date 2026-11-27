package com.kaixu.desktop

import androidx.lifecycle.ViewModelStore
import com.kaixu.app.di.AppGraph
import com.kaixu.core.platform.LocalZone
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.database.openDesktopDatabase
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.data.datasource.local.entity.newLocalId
import com.kaixu.desktop.presentation.viewmodel.home.HomeQuickCreateKind
import com.kaixu.desktop.presentation.viewmodel.home.HomeQuickCreateOutcome
import com.kaixu.desktop.presentation.viewmodel.home.HomeQuickCreateRequest
import com.kaixu.desktop.presentation.viewmodel.home.HomeViewModel
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.scheduling.WorkSessionDraft
import com.kaixu.domain.model.scheduling.WorkSessionSaveResult
import com.kaixu.domain.model.scheduling.WorkSessionStatus
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.tasks.TaskDraft
import com.kaixu.domain.model.workspaces.WorkspaceKind
import com.kaixu.domain.usecase.scheduling.AgendaKind
import com.kaixu.domain.usecase.scheduling.AgendaUseCase
import com.kaixu.domain.usecase.scheduling.AgendaWindow
import com.kaixu.domain.usecase.scheduling.FixedEventSaveResult
import com.kaixu.domain.model.scheduling.FixedEventDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class AgendaUseCaseTest {
    private val shanghai = LocalZone.of("Asia/Shanghai")

    @Test
    fun aggregatesOriginalRecordsWithinThreeDaysWithRecurrenceAndPrivacy() = runBlocking {
        val directory = Files.createTempDirectory("kaixu-agenda-data").toFile()
        val database = openDesktopDatabase(directory.resolve("agenda.db"))
        try {
            val sources = LocalDataSources(database)
            val graph = AppGraph(sources)
            val window = AgendaWindow.around("2026-11-14", shanghai)
            val space = sources.workspaces.createWorkspace(WorkspaceKind.COURSE, "归档课程")
            sources.workspaces.archiveWorkspace(space.id)
            val task = sources.tasks.createTask(Task(newLocalId(), "仅日期截止", workspaceId = space.id, deadlineDate = window.today))
            val outside = sources.tasks.createTask(Task(newLocalId(), "超出范围", deadlineAt = window.endExclusive))
            val undated = sources.tasks.createTask(Task(newLocalId(), "无截止任务"))
            val privateTask = sources.tasks.createTask(Task(newLocalId(), "其他用户", createdBy = newLocalId(), deadlineDate = window.today))
            val session = sources.workSessions.createWorkSession(WorkSession(newLocalId(), task.id, window.start - 60_000, window.start + 60_000))
            sources.workSessions.createWorkSession(session.copy(id = newLocalId(), ownerUserId = newLocalId()))
            sources.workSessions.createWorkSession(session.copy(id = newLocalId(), status = WorkSessionStatus.CANCELLED))
            val start = shanghai.atLocalDateTimeMillis("2026-11-13", 9L * 3_600_000L)
            val series = sources.fixedEvents.createEvent(
                FixedEvent(
                    newLocalId(),
                    "重复课程",
                    start,
                    start + 60_000,
                    repeatWeekdays = setOf(5, 6, 7),
                    repeatUntil = "2026-11-15",
                ),
            )
            val cancelled = sources.fixedEvents.createEvent(
                FixedEvent(
                    newLocalId(),
                    "取消课次",
                    start,
                    start + 60_000,
                    seriesId = series.id,
                    originalDate = "2026-11-14",
                    isOverride = true,
                ),
            )
            sources.fixedEvents.deleteEventById(cancelled.id)

            val result = graph.queryHomeAgenda(window, shanghai)
            val compat = AgendaUseCase(sources.tasks, sources.fixedEvents, sources.workSessions, sources.workspaces)(LOCAL_ACTOR_ID, window, shanghai)
            assertEquals(result, compat)
            assertEquals(result.entries.map { it.time }.sorted(), result.entries.map { it.time })
            assertFalse(result.entries.any { it.recordId in setOf(outside.id, undated.id, privateTask.id) })
            val deadline = result.entries.single { it.recordId == task.id }
            assertEquals(AgendaKind.TASK_DEADLINE, deadline.kind)
            assertTrue(deadline.dateOnly)
            assertNull(deadline.endsAt)
            assertEquals("归档课程 · 已归档", deadline.workspace)
            assertEquals("2026-11-14", shanghai.localDateOf(deadline.time))
            assertEquals(session.id, result.entries.single { it.kind == AgendaKind.WORK_SESSION }.recordId)
            assertEquals(2, result.entries.count { it.recordId == series.id })
            assertFalse(result.entries.filter { it.recordId == series.id }.any { shanghai.localDateOf(it.time) == "2026-11-14" })
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun homeViewModelObservesSharedRevisionMutationsAcrossTasksEventsAndWorkSessions() = runBlocking {
        val directory = Files.createTempDirectory("kaixu-home-vm-revision").toFile()
        val database = openDesktopDatabase(directory.resolve("home-vm.db"))
        val store = ViewModelStore()
        try {
            val sources = LocalDataSources(database)
            val graph = AppGraph(sources)
            val activeSpace = sources.workspaces.createWorkspace(WorkspaceKind.COURSE, "计算机系统")
            val archivedSpace = sources.workspaces.createWorkspace(WorkspaceKind.PROJECT, "归档项目").also {
                sources.workspaces.archiveWorkspace(it.id)
            }
            val fixedNow = shanghai.atLocalDateTimeMillis("2026-11-14", 10L * 3_600_000L)
            val vm = HomeViewModel(
                queryHomeAgenda = graph.queryHomeAgenda,
                taskUseCases = graph.taskUseCases,
                fixedEventUseCases = graph.fixedEventUseCases,
                zone = shanghai,
                clock = { fixedNow },
            )
            store.put("home", vm)

            val initial = withTimeout(5_000) {
                vm.uiState.first { !it.loading && it.agenda != null }
            }
            assertTrue(initial.agenda!!.entries.isEmpty())
            assertEquals(listOf(activeSpace.id), initial.activeWorkspaces.map { it.id })

            // 后台直接通过任务、固定事件、工作时段用例写入，不手动调 refresh()，HomeViewModel 自动响应更新
            val task = graph.taskUseCases.createFromDraft(TaskDraft(title = "实验报告", workspaceId = activeSpace.id))
            val createdEvent = (graph.fixedEventUseCases.create(
                FixedEventDraft(
                    title = "系统讨论班",
                    startsAt = shanghai.atLocalDateTimeMillis("2026-11-14", 14L * 3_600_000L),
                    endsAt = shanghai.atLocalDateTimeMillis("2026-11-14", 15L * 3_600_000L),
                    workspaceId = activeSpace.id,
                ),
                zone = shanghai,
            ) as FixedEventSaveResult.Saved).event
            val createdSession = (graph.workSessionUseCases.create(
                WorkSessionDraft(
                    taskId = task.id,
                    startsAt = shanghai.atLocalDateTimeMillis("2026-11-14", 16L * 3_600_000L),
                    endsAt = shanghai.atLocalDateTimeMillis("2026-11-14", 17L * 3_600_000L),
                ),
                zone = shanghai,
            ) as WorkSessionSaveResult.Saved).session

            val afterReactiveMutations = withTimeout(5_000) {
                vm.uiState.first { state ->
                    val ids = state.agenda?.entries?.map { it.recordId }.orEmpty()
                    createdEvent.id in ids && createdSession.id in ids
                }
            }
            assertEquals(2, afterReactiveMutations.agenda!!.entries.size)

            // 归档工作区快速新建任务被拒绝
            val rejected = vm.createQuickItem(
                HomeQuickCreateRequest(
                    kind = HomeQuickCreateKind.TASK,
                    title = "禁止写入",
                    workspaceId = archivedSpace.id,
                ),
            )
            assertTrue(rejected is HomeQuickCreateOutcome.Failed)

            // 快速新建固定事件后立即同步刷新到 uiState
            val quickEvent = vm.createQuickItem(
                HomeQuickCreateRequest(
                    kind = HomeQuickCreateKind.EVENT,
                    title = "快速站会",
                    eventStartsAt = shanghai.atLocalDateTimeMillis("2026-11-14", 18L * 3_600_000L),
                    eventEndsAt = shanghai.atLocalDateTimeMillis("2026-11-14", 18L * 3_600_000L + 30L * 60_000L),
                ),
            )
            assertTrue(quickEvent is HomeQuickCreateOutcome.Saved)
            assertTrue(vm.uiState.value.agenda!!.entries.any { it.title == "快速站会" })
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            database.close()
            directory.deleteRecursively()
        }
    }
}
