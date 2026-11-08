package com.kaixu.presentation.viewmodel.workspaces

import androidx.lifecycle.ViewModelStore
import com.kaixu.data.datasource.WorkspaceDataSource
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceKind
import com.kaixu.domain.model.workspaces.WorkspaceKind.COURSE
import com.kaixu.domain.model.workspaces.WorkspaceKind.PROJECT
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.model.workspaces.WorkspaceStatus.ACTIVE
import com.kaixu.domain.model.workspaces.WorkspaceStatus.ARCHIVED
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceViewModelTest {
    private lateinit var store: ViewModelStore
    private lateinit var source: FakeSource
    private lateinit var vm: WorkspaceViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        store = ViewModelStore()
        source = FakeSource()
        vm = WorkspaceViewModel(source)
        store.put("workspaces", vm)
    }

    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun filtersCombineAndDirectoryContextSurvivesDetailNavigation() = runTest {
        source.records += listOf(
            workspace("a", "Math", COURSE, createdAt = 10),
            workspace("z", "Mathematics", COURSE, createdAt = 10),
            workspace("p", "Math project", PROJECT),
            workspace("old", "Math history", COURSE, ARCHIVED),
        )
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf("p", "z", "a"), vm.uiState.value.visibleWorkspaces.map { it.id })
        vm.onKindFilterChanged(COURSE)
        vm.onSearchQueryChanged("  MATH ")
        assertEquals(listOf("z", "a"), vm.uiState.value.visibleWorkspaces.map { it.id })
        assertEquals(3, vm.uiState.value.activeCount)
        assertEquals(1, vm.uiState.value.archivedCount)
        vm.openWorkspace("a")
        assertEquals("a", vm.uiState.value.selectedWorkspace?.id)
        vm.backToDirectory()
        assertEquals(COURSE, vm.uiState.value.kindFilter)
        assertEquals("  MATH ", vm.uiState.value.searchQuery)
        vm.onStatusFilterChanged(ARCHIVED)
        assertEquals(listOf("old"), vm.uiState.value.visibleWorkspaces.map { it.id })
        vm.clearFilters()
        assertEquals(4, vm.uiState.value.visibleWorkspaces.size)
    }

    @Test
    fun delayedSaveBlocksDuplicateSubmitCancellationAndDraftChanges() = runTest {
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        source.beforeWrite = { gate.await() }
        vm.onSearchQueryChanged("另一个名称")
        vm.beginCreate()
        vm.onDraftNameChanged("  课程  ")
        vm.onDraftDescriptionChanged("说明")
        vm.saveDraft()
        vm.saveDraft()
        vm.cancelDraft()
        vm.onDraftNameChanged("不应写入")
        vm.onDraftKindChanged(PROJECT)
        vm.backToDirectory()
        runCurrent()
        assertEquals(1, source.createCalls)
        assertTrue(vm.uiState.value.isSaving)
        assertEquals("  课程  ", vm.uiState.value.draft?.name)
        assertEquals(COURSE, vm.uiState.value.draft?.kind)
        gate.complete(Unit)
        advanceUntilIdle()
        val saved = source.records.single()
        assertEquals("课程", saved.name)
        assertEquals("说明", saved.description)
        assertEquals(saved.id, vm.uiState.value.selectedId)
        assertEquals(1, vm.uiState.value.detailNavigationRevision)
        assertNull(vm.uiState.value.draft)
        assertFalse(vm.uiState.value.isSaving)
        assertTrue(vm.uiState.value.visibleWorkspaces.isEmpty())
        vm.backToDirectory()
        assertEquals("另一个名称", vm.uiState.value.searchQuery)
    }

    @Test
    fun failedSaveRetainsInputAndAReadFailureAfterRetryCannotRepeatTheWrite() = runTest {
        advanceUntilIdle()
        vm.beginCreate()
        vm.onDraftKindChanged(PROJECT)
        vm.onDraftNameChanged("项目")
        vm.onDraftDescriptionChanged("未丢失的说明")
        source.beforeWrite = { error("simulated write failure") }
        vm.saveDraft()
        advanceUntilIdle()
        assertTrue(source.records.isEmpty())
        assertEquals("项目", vm.uiState.value.draft?.name)
        assertEquals("未丢失的说明", vm.uiState.value.draft?.description)
        assertNotNull(vm.uiState.value.draft?.saveError)
        assertEquals(0, vm.uiState.value.detailNavigationRevision)
        source.beforeWrite = { }
        source.read = { error("simulated refresh failure") }
        vm.saveDraft()
        advanceUntilIdle()
        assertEquals(1, source.records.size)
        assertEquals(source.records.single().id, vm.uiState.value.selectedWorkspace?.id)
        assertNull(vm.uiState.value.draft)
        assertNotNull(vm.uiState.value.loadError)
        vm.saveDraft()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(2, source.createCalls)
        assertEquals(1, source.records.size)
        source.read = null
        vm.refresh()
        advanceUntilIdle()
        assertNull(vm.uiState.value.loadError)
        assertEquals(source.records, vm.uiState.value.allWorkspaces)
    }

    @Test
    fun editingPreservesArchivedCourseAssociationAndOriginalIdentity() = runTest {
        val course = workspace("c", "归档课程", COURSE, ARCHIVED)
        val project = workspace("p", "项目", PROJECT, linkedCourseId = "c")
        source.records += listOf(course, project)
        vm.refresh()
        advanceUntilIdle()
        vm.openWorkspace("p")
        vm.beginEdit("p")
        assertTrue(vm.uiState.value.activeCourses.isEmpty())
        assertEquals(course, vm.uiState.value.selectedDraftCourse)
        vm.onDraftKindChanged(COURSE)
        assertEquals(PROJECT, vm.uiState.value.draft?.kind)
        vm.onDraftNameChanged("编辑后")
        vm.saveDraft()
        advanceUntilIdle()
        assertEquals("p", vm.uiState.value.selectedId)
        assertEquals("c", vm.uiState.value.selectedWorkspace?.linkedCourseId)
        assertEquals(project.createdAt, vm.uiState.value.selectedWorkspace?.createdAt)
        assertEquals("编辑后", vm.uiState.value.selectedWorkspace?.name)
        assertEquals(1, vm.uiState.value.detailNavigationRevision)
        vm.beginEdit("p")
        vm.onDraftCourseChanged(null)
        vm.cancelDraft()
        assertEquals(1, vm.uiState.value.detailNavigationRevision)
        vm.beginEdit("p")
        assertEquals("c", vm.uiState.value.draft?.linkedCourseId)
    }

    @Test
    fun validatesNamesAndNewCourseReferencesWithoutDiscardingDraft() = runTest {
        source.records += listOf(
            workspace("c", "数据库", COURSE),
            workspace("archived", "旧课程", COURSE, ARCHIVED),
        )
        vm.refresh()
        advanceUntilIdle()
        vm.beginCreate()
        vm.onDraftDescriptionChanged("保持说明")
        for (name in listOf(" \t ", "字".repeat(101), " 数据库 ")) {
            vm.onDraftNameChanged(name)
            vm.saveDraft()
            assertNotNull(vm.uiState.value.draft?.nameError)
            assertEquals("保持说明", vm.uiState.value.draft?.description)
        }
        assertEquals(0, source.createCalls)
        assertEquals(0, vm.uiState.value.detailNavigationRevision)
        vm.onDraftKindChanged(PROJECT)
        vm.onDraftNameChanged("数据库")
        vm.onDraftCourseChanged("archived")
        vm.saveDraft()
        assertNotNull(vm.uiState.value.draft?.courseError)
        vm.onDraftCourseChanged(null)
        vm.saveDraft()
        advanceUntilIdle()
        assertEquals(1, source.createCalls)
        vm.beginCreate()
        vm.onDraftNameChanged("📘".repeat(100))
        vm.saveDraft()
        advanceUntilIdle()
        assertEquals(2, source.createCalls)
    }

    @Test
    fun creatingFromCoursePreselectsItsIdAndCanBeCancelledWithoutWriting() = runTest {
        source.records += workspace("c", "课程", COURSE)
        vm.refresh()
        advanceUntilIdle()
        vm.openWorkspace("c")
        vm.beginCreate("c")
        assertEquals(PROJECT, vm.uiState.value.draft?.kind)
        assertEquals("c", vm.uiState.value.draft?.linkedCourseId)
        vm.onDraftKindChanged(PROJECT)
        assertEquals("c", vm.uiState.value.draft?.linkedCourseId)
        vm.onDraftNameChanged("丢弃草稿")
        vm.cancelDraft()
        vm.beginCreate()
        assertEquals(COURSE, vm.uiState.value.draft?.kind)
        assertEquals("", vm.uiState.value.draft?.name)
        assertEquals(0, source.createCalls)
    }

    @Test
    fun archiveRequiresConfirmationAndFailuresKeepTheSavedRecord() = runTest {
        val course = workspace("c", "课程", COURSE)
        val project = workspace("p", "项目", PROJECT, linkedCourseId = "c")
        source.records += listOf(course, project)
        vm.refresh()
        advanceUntilIdle()
        vm.openWorkspace("c")
        vm.requestArchive("c")
        vm.cancelArchive()
        vm.confirmArchive()
        assertEquals(0, source.archiveCalls)
        vm.requestArchive("c")
        source.beforeWrite = { error("disk failure") }
        vm.confirmArchive()
        advanceUntilIdle()
        assertEquals(course, vm.uiState.value.selectedWorkspace)
        assertNotNull(vm.uiState.value.actionError)
        assertEquals("c", vm.uiState.value.archiveConfirmationId)
        source.beforeWrite = { }
        vm.confirmArchive()
        vm.confirmArchive()
        advanceUntilIdle()
        assertEquals(ARCHIVED, vm.uiState.value.selectedWorkspace?.status)
        assertEquals(project, vm.uiState.value.relatedProjects.single())
        vm.beginEdit("c")
        assertNull(vm.uiState.value.draft)
        vm.beginCreate("c")
        assertNull(vm.uiState.value.draft)
        vm.restoreWorkspace("c")
        advanceUntilIdle()
        assertEquals(ACTIVE, vm.uiState.value.selectedWorkspace?.status)
        assertEquals("c", vm.uiState.value.relatedProjects.single().linkedCourseId)
    }

    @Test
    fun oldReadCompletingAfterSaveCannotReplaceTheCommittedRecord() = runTest {
        source.records += workspace("base", "原课程", COURSE, createdAt = 1)
        vm.refresh()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        val snapshot = source.records.toList()
        source.read = {
            source.read = null
            withContext(NonCancellable) { gate.await() }
            snapshot
        }
        vm.refresh()
        runCurrent()
        vm.beginCreate()
        vm.onDraftNameChanged("新课程")
        vm.saveDraft()
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("新课程", vm.uiState.value.selectedWorkspace?.name)
        assertEquals(source.records.reversed(), vm.uiState.value.allWorkspaces)
        assertFalse(vm.uiState.value.isLoading)
    }

    private class FakeSource : WorkspaceDataSource {
        val records = mutableListOf<Workspace>()
        var read: (suspend () -> List<Workspace>)? = null
        var beforeWrite: suspend () -> Unit = { }
        var createCalls = 0
        var archiveCalls = 0

        override suspend fun listWorkspaces(status: WorkspaceStatus?): List<Workspace> =
            (read?.invoke() ?: records.toList()).filter { status == null || it.status == status }

        override suspend fun findWorkspaceById(id: String): Workspace? = records.find { it.id == id }

        override suspend fun listProjectsForCourse(courseId: String, status: WorkspaceStatus?): List<Workspace> =
            records.filter { it.linkedCourseId == courseId && (status == null || it.status == status) }

        override suspend fun createWorkspace(kind: WorkspaceKind, name: String, description: String?, linkedCourseId: String?): Workspace {
            ++createCalls
            beforeWrite()
            return workspace("new-$createCalls", name, kind, linkedCourseId = linkedCourseId)
                .copy(description = description).also { records += it }
        }

        override suspend fun updateWorkspace(id: String, name: String, description: String?, linkedCourseId: String?): Boolean {
            beforeWrite()
            val index = records.indexOfFirst { it.id == id && it.status == ACTIVE }
            if (index == -1) return false
            records[index] = records[index].copy(name = name, description = description, linkedCourseId = linkedCourseId)
            return true
        }

        override suspend fun archiveWorkspace(id: String): Boolean {
            ++archiveCalls
            beforeWrite()
            val index = records.indexOfFirst { it.id == id && it.status == ACTIVE }
            if (index == -1) return false
            records[index] = records[index].copy(status = ARCHIVED, archivedAt = 500)
            return true
        }

        override suspend fun restoreWorkspace(id: String): Boolean {
            beforeWrite()
            val index = records.indexOfFirst { it.id == id && it.status == ARCHIVED }
            if (index == -1) return false
            records[index] = records[index].copy(status = ACTIVE, archivedAt = null)
            return true
        }
    }

    private companion object {
        fun workspace(
            id: String,
            name: String,
            kind: WorkspaceKind,
            status: WorkspaceStatus = ACTIVE,
            linkedCourseId: String? = null,
            createdAt: Long = 100,
        ) = Workspace(id, "local", kind, name, null, linkedCourseId, status, createdAt, createdAt, if (status == ARCHIVED) 200 else null)
    }
}
