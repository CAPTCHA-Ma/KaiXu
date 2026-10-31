package com.kaixu.presentation.viewmodel.tasks

import androidx.lifecycle.ViewModelStore
import com.kaixu.data.repository.DefaultTaskRepository
import com.kaixu.data.datasource.memory.InMemoryTaskDataSource
import com.kaixu.domain.usecase.tasks.CreateTaskUseCase
import com.kaixu.domain.usecase.tasks.ObserveTasksUseCase
import com.kaixu.domain.usecase.tasks.RefreshTasksUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TaskEditorViewModelTest {
    private lateinit var store: ViewModelStore
    private lateinit var source: InMemoryTaskDataSource
    private lateinit var vm: TaskEditorViewModel

    @BeforeTest fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        store = ViewModelStore()
        source = InMemoryTaskDataSource(latencyMillis = 600)
        val repository = DefaultTaskRepository(source)
        vm = TaskEditorViewModel(
            CreateTaskUseCase(repository), ObserveTasksUseCase(repository), RefreshTasksUseCase(repository),
        )
        store.put("editor", vm)
    }

    @AfterTest fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test fun blankTitleDoesNotCreateTask() = runTest {
        advanceUntilIdle()
        vm.onTitleChanged("   ")
        vm.onSaveClicked()
        advanceUntilIdle()
        assertEquals("请输入任务标题", vm.uiState.value.errorMessage)
        assertTrue(source.listTasks().isEmpty())
        assertEquals("   ", vm.uiState.value.draftTitle)
    }

    @Test fun savesTrimmedTitleAndClearsDraftOnlyAfterSuccess() = runTest {
        advanceUntilIdle()
        vm.onTitleChanged("  访谈准备  ")
        vm.onSaveClicked()
        assertTrue(vm.uiState.value.isSaving)
        assertEquals("  访谈准备  ", vm.uiState.value.draftTitle)
        advanceUntilIdle()
        assertEquals("访谈准备", vm.uiState.value.tasks.single().title)
        assertEquals("", vm.uiState.value.draftTitle)
        assertFalse(vm.uiState.value.isSaving)
    }

    @Test fun failurePreservesDraftAndRetryCreatesExactlyOneTask() = runTest {
        advanceUntilIdle()
        source.failNextCreate()
        vm.onTitleChanged("准备演示")
        vm.onSaveClicked()
        advanceUntilIdle()
        assertEquals("准备演示", vm.uiState.value.draftTitle)
        assertNotNull(vm.uiState.value.errorMessage)
        assertTrue(source.listTasks().isEmpty())
        vm.onSaveClicked()
        advanceUntilIdle()
        assertEquals(1, source.listTasks().size)
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test fun repeatedClickDuringSaveDoesNotDuplicate() = runTest {
        advanceUntilIdle()
        vm.onTitleChanged("同一次操作")
        repeat(3) { vm.onSaveClicked() }
        advanceUntilIdle()
        assertEquals(1, source.listTasks().size)
    }

    @Test fun cancellingPendingDemoSaveLeavesDraftAndStorageUnchanged() = runTest {
        advanceUntilIdle()
        vm.onTitleChanged("稍后保存")
        vm.onSaveClicked()
        runCurrent()
        vm.onCancelSaveClicked()
        advanceUntilIdle()
        assertEquals("稍后保存", vm.uiState.value.draftTitle)
        assertTrue(source.listTasks().isEmpty())
        assertFalse(vm.uiState.value.isSaving)
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test fun overlongTitleIsRejected() = runTest {
        advanceUntilIdle()
        vm.onTitleChanged("字".repeat(121))
        vm.onSaveClicked()
        advanceUntilIdle()
        assertTrue(source.listTasks().isEmpty())
        assertNotNull(vm.uiState.value.errorMessage)
    }

    @Test fun immediateCancellationDoesNotLeaveSavingStateStuck() = runTest {
        advanceUntilIdle()
        vm.onTitleChanged("立即取消")
        vm.onSaveClicked()
        vm.onCancelSaveClicked()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isSaving)
        assertEquals("立即取消", vm.uiState.value.draftTitle)
        assertTrue(source.listTasks().isEmpty())
        vm.onSaveClicked()
        advanceUntilIdle()
        assertEquals(1, source.listTasks().size)
    }

    @Test fun maximumLengthTitleIsAccepted() = runTest {
        advanceUntilIdle()
        vm.onTitleChanged("字".repeat(120))
        vm.onSaveClicked()
        advanceUntilIdle()
        assertEquals(120, vm.uiState.value.tasks.single().title.length)
    }
}
