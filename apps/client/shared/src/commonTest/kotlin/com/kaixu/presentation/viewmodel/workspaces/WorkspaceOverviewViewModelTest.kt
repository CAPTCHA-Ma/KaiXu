package com.kaixu.presentation.viewmodel.workspaces

import androidx.lifecycle.ViewModelStore
import com.kaixu.data.datasource.FixedEventDataSource
import com.kaixu.data.datasource.TaskDataSource
import com.kaixu.data.datasource.WorkSessionDataSource
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.tasks.TaskStatus
import com.kaixu.domain.usecase.workspaces.WorkspaceDeadlineFilter
import com.kaixu.domain.usecase.workspaces.WorkspaceDeadlineWindow
import com.kaixu.domain.usecase.workspaces.WorkspaceOverviewUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceOverviewViewModelTest {
    private lateinit var store: ViewModelStore
    private lateinit var source: OverviewSources
    private lateinit var vm: WorkspaceOverviewViewModel
    private var windowReads = 0

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        store = ViewModelStore()
        source = OverviewSources()
        windowReads = 0
        vm = WorkspaceOverviewViewModel(WorkspaceOverviewUseCase(source, source, source), "me") {
            windowReads++
            WorkspaceDeadlineWindow("2026-11-08", 1_794_099_600_000L, "2026-11-15", 1_794_672_000_000L)
        }
        store.put("overview", vm)
    }

    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun refreshingSameWorkspaceKeepsFiltersAndReadsUpdatedOriginalRecords() = runTest {
        val task = Task("t", "原标题", workspaceId = "a", status = TaskStatus.IN_PROGRESS, deadlineDate = "2026-11-08")
        source.records = listOf(task)
        vm.refresh("a")
        advanceUntilIdle()
        vm.onStatusFilterChanged(TaskStatus.IN_PROGRESS)
        vm.onDeadlineFilterChanged(WorkspaceDeadlineFilter.NEXT_SEVEN_DAYS)
        advanceUntilIdle()
        assertSame(task, assertNotNull(vm.uiState.value.overview).tasks.single())

        val edited = task.copy(title = "详情中修改后的标题")
        source.records = listOf(edited)
        val previousWindowReads = windowReads
        vm.refresh()
        advanceUntilIdle()

        assertEquals(TaskStatus.IN_PROGRESS, vm.uiState.value.statusFilter)
        assertEquals(WorkspaceDeadlineFilter.NEXT_SEVEN_DAYS, vm.uiState.value.deadlineFilter)
        assertSame(edited, assertNotNull(vm.uiState.value.overview).tasks.single())
        assertEquals(previousWindowReads + 1, windowReads)
    }

    @Test
    fun switchingWorkspaceClearsSnapshotAndFiltersAndIgnoresLateSuccess() = runTest {
        source.records = listOf(Task("a-task", "旧工作区", workspaceId = "a"))
        vm.refresh("a")
        advanceUntilIdle()
        vm.onStatusFilterChanged(TaskStatus.TODO)
        vm.onDeadlineFilterChanged(WorkspaceDeadlineFilter.NO_DEADLINE)
        advanceUntilIdle()
        val late = CompletableDeferred<List<Task>>()
        source.read = { id ->
            if (id == "a") withContext(NonCancellable) { late.await() }
            else listOf(Task("b-task", "新工作区", workspaceId = "b"))
        }
        vm.refresh()
        runCurrent()

        vm.refresh("b")
        assertEquals("b", vm.uiState.value.workspaceId)
        assertNull(vm.uiState.value.overview)
        assertNull(vm.uiState.value.statusFilter)
        assertEquals(WorkspaceDeadlineFilter.ALL, vm.uiState.value.deadlineFilter)
        assertTrue(vm.uiState.value.isLoading)
        runCurrent()
        late.complete(listOf(Task("late-a", "迟到的旧结果", workspaceId = "a")))
        advanceUntilIdle()

        assertEquals("b", vm.uiState.value.workspaceId)
        assertEquals("b-task", assertNotNull(vm.uiState.value.overview).tasks.single().id)
        assertNull(vm.uiState.value.loadError)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun failedRefreshCanRetryAndFilterFailureDoesNotExposePreviousFilterResults() = runTest {
        source.records = listOf(Task("t", "任务", workspaceId = "a"))
        vm.refresh("a")
        advanceUntilIdle()
        val previous = vm.uiState.value.overview
        source.read = { error("模拟读取失败") }
        vm.refresh()
        advanceUntilIdle()
        assertSame(previous, vm.uiState.value.overview)
        assertNotNull(vm.uiState.value.loadError)
        assertFalse(vm.uiState.value.isLoading)

        vm.onStatusFilterChanged(TaskStatus.COMPLETED)
        assertNull(vm.uiState.value.overview)
        advanceUntilIdle()
        assertNull(vm.uiState.value.overview)
        assertNotNull(vm.uiState.value.loadError)

        val completed = Task("t", "已完成", workspaceId = "a", status = TaskStatus.COMPLETED, completedAt = 1L)
        source.read = { listOf(completed) }
        vm.refresh()
        advanceUntilIdle()
        assertSame(completed, assertNotNull(vm.uiState.value.overview).tasks.single())
        assertNull(vm.uiState.value.loadError)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun closingWorkspaceIgnoresLateFailureAndCanOpenAnotherWorkspace() = runTest {
        val finishOldRead = CompletableDeferred<Unit>()
        source.read = {
            withContext(NonCancellable) {
                finishOldRead.await()
                error("旧工作区的读取错误")
            }
        }
        vm.refresh("a")
        runCurrent()
        vm.refresh(null)
        assertNull(vm.uiState.value.workspaceId)
        assertNull(vm.uiState.value.overview)
        assertFalse(vm.uiState.value.isLoading)
        finishOldRead.complete(Unit)
        advanceUntilIdle()
        assertNull(vm.uiState.value.loadError)

        source.read = { emptyList() }
        vm.refresh("b")
        advanceUntilIdle()
        assertEquals("b", assertNotNull(vm.uiState.value.overview).workspaceId)
        assertTrue(assertNotNull(vm.uiState.value.overview).tasks.isEmpty())
    }

    private class OverviewSources : TaskDataSource, FixedEventDataSource, WorkSessionDataSource {
        var records = emptyList<Task>()
        var read: suspend (String) -> List<Task> = { id -> records.filter { it.workspaceId == id } }
        override suspend fun listTasksByWorkspace(workspaceId: String) = read(workspaceId)
        override suspend fun listEventsByWorkspace(workspaceId: String) = emptyList<FixedEvent>()
        override suspend fun listWorkSessions() = emptyList<WorkSession>()

        override suspend fun listTasks(): List<Task> = error("未使用的读取")
        override suspend fun findTaskById(id: String): Task? = error("详情由桌面层处理")
        override suspend fun createTask(title: String): Task = error("概览为只读")
        override suspend fun createTask(value: Task): Task = error("概览为只读")
        override suspend fun updateTask(value: Task): Boolean = error("概览为只读")
        override suspend fun deleteTaskById(id: String): Boolean = error("概览为只读")
        override suspend fun listEvents(): List<FixedEvent> = error("未使用的读取")
        override suspend fun findEventById(id: String): FixedEvent? = error("详情由桌面层处理")
        override suspend fun createEvent(value: FixedEvent): FixedEvent = error("概览为只读")
        override suspend fun updateEvent(value: FixedEvent): Boolean = error("概览为只读")
        override suspend fun deleteEventById(id: String): Boolean = error("概览为只读")
        override suspend fun listEventsInRange(ownerId: String, start: Long, end: Long): List<FixedEvent> = error("未使用的读取")
        override suspend fun findWorkSessionById(id: String): WorkSession? = error("详情由桌面层处理")
        override suspend fun createWorkSession(value: WorkSession): WorkSession = error("概览为只读")
        override suspend fun updateWorkSession(value: WorkSession): Boolean = error("概览为只读")
        override suspend fun deleteWorkSessionById(id: String): Boolean = error("概览为只读")
        override suspend fun listWorkSessionsByTask(taskId: String): List<WorkSession> = error("未使用的读取")
        override suspend fun listWorkSessionsInRange(ownerId: String, start: Long, end: Long): List<WorkSession> = error("未使用的读取")
    }
}
