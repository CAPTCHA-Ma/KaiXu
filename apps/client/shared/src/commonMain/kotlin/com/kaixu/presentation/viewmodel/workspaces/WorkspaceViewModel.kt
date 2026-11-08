package com.kaixu.presentation.viewmodel.workspaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaixu.data.datasource.WorkspaceDataSource
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceKind
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.presentation.state.workspaces.WorkspaceDraft
import com.kaixu.presentation.state.workspaces.WorkspaceUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WorkspaceViewModel(private val source: WorkspaceDataSource) : ViewModel() {
    private val _uiState = MutableStateFlow(WorkspaceUiState())
    val uiState = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private var loadGeneration = 0L

    init {
        refresh()
    }

    fun refresh() {
        if (_uiState.value.isSaving) return
        loadJob?.cancel()
        val generation = ++loadGeneration
        _uiState.update { it.copy(isLoading = true, loadError = null) }
        loadJob = viewModelScope.launch {
            try {
                val records = source.listWorkspaces(null)
                if (generation == loadGeneration) {
                    _uiState.update { it.copy(allWorkspaces = sorted(records), loadError = null) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == loadGeneration) {
                    _uiState.update { it.copy(loadError = "读取工作区失败，已保存的内容将保留。请重试读取。") }
                }
            } finally {
                if (generation == loadGeneration) _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun onStatusFilterChanged(value: WorkspaceStatus?) {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(statusFilter = value) }
    }

    fun onKindFilterChanged(value: WorkspaceKind?) {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(kindFilter = value) }
    }

    fun onSearchQueryChanged(value: String) {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(searchQuery = value) }
    }

    fun clearFilters() {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(statusFilter = null, kindFilter = null, searchQuery = "") }
    }

    fun openWorkspace(id: String) {
        val state = _uiState.value
        if (state.isSaving || state.draft != null) return
        if (state.allWorkspaces.none { it.id == id }) {
            _uiState.update { it.copy(actionError = "未找到工作区，请刷新后重试。") }
            return
        }
        _uiState.update { it.copy(selectedId = id, actionError = null, archiveConfirmationId = null) }
    }

    fun backToDirectory() {
        if (_uiState.value.isSaving || _uiState.value.draft != null) return
        _uiState.update { it.copy(selectedId = null, actionError = null, archiveConfirmationId = null) }
    }

    fun beginCreate(courseId: String? = null) {
        val state = _uiState.value
        if (state.isSaving || state.draft != null || state.isLoading && state.allWorkspaces.isEmpty()) return
        if (courseId != null && state.activeCourses.none { it.id == courseId }) {
            _uiState.update { it.copy(actionError = "只能在进行中的课程内创建关联项目。") }
            return
        }
        val draft = WorkspaceDraft(
            kind = if (courseId == null) WorkspaceKind.COURSE else WorkspaceKind.PROJECT,
            linkedCourseId = courseId,
        )
        _uiState.update { it.copy(draft = draft, actionError = null, archiveConfirmationId = null) }
    }

    fun beginEdit(id: String) {
        val state = _uiState.value
        if (state.isSaving || state.draft != null) return
        val record = state.allWorkspaces.find { it.id == id } ?: return
        if (record.status != WorkspaceStatus.ACTIVE) return
        _uiState.update {
            it.copy(
                draft = WorkspaceDraft(record.id, record.kind, record.name, record.description.orEmpty(), record.linkedCourseId),
                actionError = null,
                archiveConfirmationId = null,
            )
        }
    }

    fun onDraftKindChanged(value: WorkspaceKind) {
        changeDraft {
            if (it.workspaceId != null || it.kind == value) it
            else it.copy(kind = value, linkedCourseId = null, nameError = null, courseError = null, saveError = null)
        }
    }

    fun onDraftNameChanged(value: String) {
        changeDraft { it.copy(name = value, nameError = null, saveError = null) }
    }

    fun onDraftDescriptionChanged(value: String) {
        changeDraft { it.copy(description = value, saveError = null) }
    }

    fun onDraftCourseChanged(value: String?) {
        changeDraft {
            if (it.kind == WorkspaceKind.COURSE) it
            else it.copy(linkedCourseId = value, courseError = null, saveError = null)
        }
    }

    fun cancelDraft() {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(draft = null) }
    }

    fun saveDraft() {
        val state = _uiState.value
        val draft = state.draft ?: return
        if (state.isSaving) return
        val name = draft.name.trim()
        val pairs = (1 until name.length).count { name[it - 1].isHighSurrogate() && name[it].isLowSurrogate() }
        val nameError = when {
            name.isEmpty() -> "请输入工作区名称"
            name.length - pairs > 100 -> "工作区名称最多 100 个字符"
            state.allWorkspaces.any { it.kind == draft.kind && it.name == name && it.id != draft.workspaceId } ->
                "该类型的工作区已使用这个名称"
            else -> null
        }
        val original = state.allWorkspaces.find { it.id == draft.workspaceId }
        val keepsOriginalLink = original != null && original.linkedCourseId == draft.linkedCourseId
        val courseError = if (draft.linkedCourseId != null && !keepsOriginalLink &&
            state.activeCourses.none { it.id == draft.linkedCourseId }
        ) "请选择进行中的课程，或设为独立项目。" else null
        if (nameError != null || courseError != null) {
            _uiState.update { it.copy(draft = draft.copy(nameError = nameError, courseError = courseError, saveError = null)) }
            return
        }
        beginWrite()
        viewModelScope.launch {
            try {
                val description = draft.description.takeUnless { it.isBlank() }
                val saved = if (draft.workspaceId == null) {
                    source.createWorkspace(draft.kind, name, description, draft.linkedCourseId)
                } else {
                    checkNotNull(original) { "工作区已不可用，请关闭表单并刷新。" }
                    check(source.updateWorkspace(original.id, name, description, draft.linkedCourseId)) {
                        "工作区已归档或不可用，请关闭表单并刷新。"
                    }
                    original.copy(name = name, description = description, linkedCourseId = draft.linkedCourseId)
                }
                _uiState.update {
                    it.copy(
                        allWorkspaces = replacing(it.allWorkspaces, saved),
                        selectedId = saved.id,
                        detailNavigationRevision = it.detailNavigationRevision + 1,
                        draft = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = if (error is IllegalArgumentException || error is IllegalStateException) {
                    error.message ?: "保存失败，输入已保留。请重试。"
                } else "保存失败，输入已保留。请重试。"
                _uiState.update { it.copy(draft = draft.copy(saveError = message)) }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
            // 写成功先退出表单；后续读取失败只重试读取，避免重复创建。
            if (_uiState.value.draft == null) refresh()
        }
    }

    fun requestArchive(id: String) {
        val state = _uiState.value
        if (state.isSaving || state.draft != null) return
        if (state.allWorkspaces.none { it.id == id && it.status == WorkspaceStatus.ACTIVE }) return
        _uiState.update { it.copy(archiveConfirmationId = id, actionError = null) }
    }

    fun cancelArchive() {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(archiveConfirmationId = null) }
    }

    fun confirmArchive() {
        val id = _uiState.value.archiveConfirmationId ?: return
        changeArchiveStatus(id, archive = true)
    }

    fun restoreWorkspace(id: String) {
        changeArchiveStatus(id, archive = false)
    }

    fun clearActionError() {
        _uiState.update { it.copy(actionError = null) }
    }

    private fun changeDraft(change: (WorkspaceDraft) -> WorkspaceDraft) {
        if (_uiState.value.isSaving) return
        _uiState.update { state -> state.copy(draft = state.draft?.let(change)) }
    }

    private fun beginWrite() {
        ++loadGeneration
        loadJob?.cancel()
        _uiState.update { it.copy(isSaving = true, isLoading = false, loadError = null, actionError = null) }
    }

    private fun changeArchiveStatus(id: String, archive: Boolean) {
        val state = _uiState.value
        if (state.isSaving || state.draft != null) return
        val record = state.allWorkspaces.find { it.id == id } ?: return
        val expected = if (archive) WorkspaceStatus.ACTIVE else WorkspaceStatus.ARCHIVED
        if (record.status != expected) return
        beginWrite()
        viewModelScope.launch {
            var committed = false
            try {
                val changed = if (archive) source.archiveWorkspace(id) else source.restoreWorkspace(id)
                check(changed) { "工作区状态已变化，请刷新后重试。" }
                val saved = record.copy(status = if (archive) WorkspaceStatus.ARCHIVED else WorkspaceStatus.ACTIVE)
                _uiState.update {
                    it.copy(allWorkspaces = replacing(it.allWorkspaces, saved), archiveConfirmationId = null)
                }
                committed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(actionError = "操作失败，原工作区已保留。请重试。") }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
            if (committed) refresh()
        }
    }

    private fun replacing(records: List<Workspace>, saved: Workspace): List<Workspace> =
        sorted(records.filterNot { it.id == saved.id } + saved)

    private fun sorted(records: List<Workspace>): List<Workspace> =
        records.sortedWith(compareByDescending<Workspace> { it.createdAt }.thenByDescending { it.id })
}
