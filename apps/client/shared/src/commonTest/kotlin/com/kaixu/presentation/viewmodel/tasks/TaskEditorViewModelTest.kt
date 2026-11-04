package com.kaixu.presentation.viewmodel.tasks

import androidx.lifecycle.ViewModelStore
import com.kaixu.testing.TestTaskRepository
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
    private lateinit var repository: TestTaskRepository
    private lateinit var vm: TaskEditorViewModel

    @BeforeTest fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        store = ViewModelStore()
        repository = TestTaskRepository(latencyMillis = 600)
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
        assertTrue(repository.tasks.value.isEmpty())
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
        repository.failNextCreate()
        vm.onTitleChanged("准备演示")
        vm.onSaveClicked()
        advanceUntilIdle()
        assertEquals("准备演示", vm.uiState.value.draftTitle)
        assertNotNull(vm.uiState.value.errorMessage)
        assertTrue(repository.tasks.value.isEmpty())
        vm.onSaveClicked()
        advanceUntilIdle()
        assertEquals(1, repository.tasks.value.size)
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test fun repeatedClickDuringSaveDoesNotDuplicate() = runTest {
        advanceUntilIdle()
        vm.onTitleChanged("同一次操作")
        repeat(3) { vm.onSaveClicked() }
        advanceUntilIdle()
        assertEquals(1, repository.tasks.value.size)
    }

    @Test fun cancellingBeforeCommitLeavesDraftAndStorageUnchanged() = runTest {
        advanceUntilIdle()
        vm.onTitleChanged("稍后保存")
        vm.onSaveClicked()
        runCurrent()
        vm.onCancelSaveClicked()
        advanceUntilIdle()
        assertEquals("稍后保存", vm.uiState.value.draftTitle)
        assertTrue(repository.tasks.value.isEmpty())
        assertFalse(vm.uiState.value.isSaving)
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test fun overlongTitleIsRejected() = runTest {
        advanceUntilIdle()
        vm.onTitleChanged("字".repeat(121))
        vm.onSaveClicked()
        advanceUntilIdle()
        assertTrue(repository.tasks.value.isEmpty())
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
        assertTrue(repository.tasks.value.isEmpty())
        vm.onSaveClicked()
        advanceUntilIdle()
        assertEquals(1, repository.tasks.value.size)
    }

    @Test fun maximumLengthTitleIsAccepted() = runTest {
        advanceUntilIdle()
        vm.onTitleChanged("字".repeat(120))
        vm.onSaveClicked()
        advanceUntilIdle()
        assertEquals(120, vm.uiState.value.tasks.single().title.length)
    }
}
