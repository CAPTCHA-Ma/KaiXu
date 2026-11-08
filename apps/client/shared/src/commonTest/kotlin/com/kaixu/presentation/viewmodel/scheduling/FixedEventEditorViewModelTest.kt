package com.kaixu.presentation.viewmodel.scheduling

import androidx.lifecycle.ViewModelStore
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.usecase.scheduling.CreateFixedEventUseCase
import com.kaixu.domain.usecase.scheduling.DeleteFixedEventUseCase
import com.kaixu.domain.usecase.scheduling.FindFixedEventByIdUseCase
import com.kaixu.domain.usecase.scheduling.ObserveFixedEventsUseCase
import com.kaixu.domain.usecase.scheduling.RefreshFixedEventsUseCase
import com.kaixu.domain.usecase.scheduling.UpdateFixedEventUseCase
import com.kaixu.domain.validation.scheduling.ShanghaiTime
import com.kaixu.testing.TestFixedEventRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FixedEventEditorViewModelTest {
    private lateinit var store: ViewModelStore
    private lateinit var repository: TestFixedEventRepository
    private lateinit var vm: FixedEventEditorViewModel

    private val event = FixedEvent(
        id = "e1",
        title = "课程",
        startsAt = ShanghaiTime.parseOrNull("2026-11-07 09:00")!!,
        endsAt = ShanghaiTime.parseOrNull("2026-11-07 10:00")!!,
        description = "说明",
        workspaceId = "w1",
        reminderOffsetMinutes = 15,
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        store = ViewModelStore()
        repository = TestFixedEventRepository()
    }

    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun start() {
        vm = FixedEventEditorViewModel(
            ObserveFixedEventsUseCase(repository),
            RefreshFixedEventsUseCase(repository),
            CreateFixedEventUseCase(repository),
            UpdateFixedEventUseCase(repository),
            DeleteFixedEventUseCase(repository),
            FindFixedEventByIdUseCase(repository),
        )
        store.put("editor", vm)
    }

    private fun fillDraft(title: String = "课程") {
        vm.onTitleChanged(title)
        vm.onStartChanged("2026-11-07 09:00")
        vm.onEndChanged("2026-11-07 10:00")
    }

    @Test
    fun loadsEventsOnInit() = runTest {
        repository.refreshResult = listOf(event)
        start()
        advanceUntilIdle()
        assertEquals(listOf(event), vm.uiState.value.events)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun blankTitleDoesNotCreateEvent() = runTest {
        start()
        advanceUntilIdle()
        fillDraft(title = "   ")
        vm.onSaveClicked()
        advanceUntilIdle()
        assertEquals(0, repository.createCalls)
        assertNotNull(vm.uiState.value.errorMessage)
        assertEquals("   ", vm.uiState.value.draftTitle)
    }

    @Test
    fun invalidTimeTextIsRejected() = runTest {
        start()
        advanceUntilIdle()
        vm.onTitleChanged("课程")
        vm.onStartChanged("明天九点")
        vm.onEndChanged("2026-11-07 10:00")
        vm.onSaveClicked()
        advanceUntilIdle()
        assertEquals(0, repository.createCalls)
        assertEquals("时间格式应为 yyyy-MM-dd HH:mm", vm.uiState.value.errorMessage)
    }

    @Test
    fun createsEventAndClearsDraftOnSuccess() = runTest {
        start()
        advanceUntilIdle()
        fillDraft(title = "  课程  ")
        vm.onSaveClicked()
        advanceUntilIdle()
        assertEquals(1, repository.createCalls)
        assertEquals("课程", repository.lastSaved?.title)
        assertEquals("", vm.uiState.value.draftTitle)
        assertNotNull(vm.uiState.value.savedMessage)
        assertFalse(vm.uiState.value.isSaving)
    }

    @Test
    fun repeatedSaveClickCreatesOnlyOnce() = runTest {
        start()
        advanceUntilIdle()
        fillDraft()
        repeat(3) { vm.onSaveClicked() }
        advanceUntilIdle()
        assertEquals(1, repository.createCalls)
    }

    @Test
    fun saveFailureKeepsDraftAndShowsError() = runTest {
        start()
        advanceUntilIdle()
        repository.failNextWrite = true
        fillDraft(title = "准备演示")
        vm.onSaveClicked()
        advanceUntilIdle()
        assertEquals("准备演示", vm.uiState.value.draftTitle)
        assertNotNull(vm.uiState.value.errorMessage)
        assertTrue(repository.events.value.isEmpty())
    }

    @Test
    fun clickingEventLoadsDraftAndUpdatesExistingRecord() = runTest {
        repository.refreshResult = listOf(event)
        start()
        advanceUntilIdle()

        vm.onEventClicked("e1")
        advanceUntilIdle()
        assertEquals("e1", vm.uiState.value.editingId)
        assertEquals("课程", vm.uiState.value.draftTitle)
        assertEquals("2026-11-07 09:00", vm.uiState.value.draftStart)

        vm.onTitleChanged("课程（改）")
        vm.onSaveClicked()
        advanceUntilIdle()

        assertEquals(1, repository.updateCalls)
        assertEquals(0, repository.createCalls)
        assertEquals("课程（改）", repository.lastSaved?.title)
        assertNull(vm.uiState.value.editingId)
    }

    @Test
    fun editPreservesFieldsNotShownInForm() = runTest {
        repository.refreshResult = listOf(event)
        start()
        advanceUntilIdle()

        vm.onEventClicked("e1")
        advanceUntilIdle()
        vm.onTitleChanged("改名")
        vm.onSaveClicked()
        advanceUntilIdle()

        assertEquals("w1", repository.lastSaved?.workspaceId)
        assertEquals(15, repository.lastSaved?.reminderOffsetMinutes)
    }

    @Test
    fun newClickedClearsEditingDraft() = runTest {
        repository.refreshResult = listOf(event)
        start()
        advanceUntilIdle()

        vm.onEventClicked("e1")
        advanceUntilIdle()
        vm.onNewClicked()

        assertNull(vm.uiState.value.editingId)
        assertEquals("", vm.uiState.value.draftTitle)
    }

    @Test
    fun deleteRequiresConfirmation() = runTest {
        repository.refreshResult = listOf(event)
        start()
        advanceUntilIdle()

        vm.onDeleteRequested("e1")
        assertEquals("e1", vm.uiState.value.pendingDeleteId)
        assertEquals(0, repository.deleteCalls)

        vm.onDeleteCancelled()
        assertNull(vm.uiState.value.pendingDeleteId)
        assertEquals(0, repository.deleteCalls)
    }

    @Test
    fun deleteConfirmedRemovesEventAndClosesDialog() = runTest {
        repository.refreshResult = listOf(event)
        start()
        advanceUntilIdle()

        vm.onDeleteRequested("e1")
        vm.onDeleteConfirmed()
        advanceUntilIdle()

        assertEquals(1, repository.deleteCalls)
        assertTrue(vm.uiState.value.events.isEmpty())
        assertNull(vm.uiState.value.pendingDeleteId)
        assertNotNull(vm.uiState.value.savedMessage)
    }

    @Test
    fun deletingEditedEventClearsEditor() = runTest {
        repository.refreshResult = listOf(event)
        start()
        advanceUntilIdle()

        vm.onEventClicked("e1")
        advanceUntilIdle()
        vm.onDeleteRequested("e1")
        vm.onDeleteConfirmed()
        advanceUntilIdle()

        assertNull(vm.uiState.value.editingId)
        assertEquals("", vm.uiState.value.draftTitle)
        assertTrue(vm.uiState.value.events.isEmpty())
    }

    @Test
    fun deleteFailureKeepsEventAndShowsError() = runTest {
        repository.refreshResult = listOf(event)
        start()
        advanceUntilIdle()

        repository.failNextWrite = true
        vm.onDeleteRequested("e1")
        vm.onDeleteConfirmed()
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.events.size)
        assertNotNull(vm.uiState.value.errorMessage)
        assertNull(vm.uiState.value.pendingDeleteId)
    }

    @Test
    fun retryAfterLoadFailureReloadsEvents() = runTest {
        start()
        advanceUntilIdle()

        repository.failNextRefresh = true
        vm.onRetryClicked()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.errorMessage)

        repository.refreshResult = listOf(event)
        vm.onRetryClicked()
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.events.size)
        assertNull(vm.uiState.value.errorMessage)
    }
}
