package com.kaixu.presentation.viewmodel.scheduling

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.usecase.scheduling.CreateFixedEventUseCase
import com.kaixu.domain.usecase.scheduling.DeleteFixedEventUseCase
import com.kaixu.domain.usecase.scheduling.FindFixedEventByIdUseCase
import com.kaixu.domain.usecase.scheduling.ObserveFixedEventsUseCase
import com.kaixu.domain.usecase.scheduling.RefreshFixedEventsUseCase
import com.kaixu.domain.usecase.scheduling.UpdateFixedEventUseCase
import com.kaixu.domain.validation.scheduling.FixedEventForm
import com.kaixu.domain.validation.scheduling.ShanghaiTime
import com.kaixu.presentation.state.scheduling.FixedEventEditorUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 固定事件编辑页：订阅列表、维护草稿、执行创建/更新/删除。
 * 时间文本只在这一层转成 UTC 毫秒，domain 不接触 "yyyy-MM-dd HH:mm"。
 */
class FixedEventEditorViewModel(
    private val observeFixedEvents: ObserveFixedEventsUseCase,
    private val refreshFixedEvents: RefreshFixedEventsUseCase,
    private val createFixedEvent: CreateFixedEventUseCase,
    private val updateFixedEvent: UpdateFixedEventUseCase,
    private val deleteFixedEvent: DeleteFixedEventUseCase,
    private val findFixedEventById: FindFixedEventByIdUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(FixedEventEditorUiState())
    val uiState = _uiState.asStateFlow()

    /** 正在编辑的完整事件；用于保留表单未展示的字段（工作区、提醒、重复等）。 */
    private var editingEvent: FixedEvent? = null
    private var saveJob: Job? = null
    private var deleteJob: Job? = null

    init {
        viewModelScope.launch {
            observeFixedEvents().collect { events ->
                _uiState.update { it.copy(events = events) }
            }
        }
        loadEvents()
    }

    fun onRetryClicked() {
        if (_uiState.value.isLoading) return
        loadEvents()
    }

    fun onNewClicked() {
        val state = _uiState.value
        if (state.isSaving || state.isDeleting) return
        editingEvent = null
        _uiState.update {
            it.copy(
                editingId = null,
                draftTitle = "",
                draftStart = "",
                draftEnd = "",
                draftDescription = "",
                errorMessage = null,
                savedMessage = null,
            )
        }
    }

    /** 点击列表项进入编辑：按 ID 读取最新记录，草稿填入表单。 */
    fun onEventClicked(id: String) {
        val state = _uiState.value
        if (state.isSaving || state.isDeleting) return
        viewModelScope.launch {
            val event = try {
                findFixedEventById(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (event == null) {
                _uiState.update { it.copy(errorMessage = "事件不存在或已被删除") }
                return@launch
            }
            editingEvent = event
            _uiState.update {
                it.copy(
                    editingId = event.id,
                    draftTitle = event.title,
                    draftStart = ShanghaiTime.format(event.startsAt),
                    draftEnd = ShanghaiTime.format(event.endsAt),
                    draftDescription = event.description.orEmpty(),
                    errorMessage = null,
                    savedMessage = null,
                )
            }
        }
    }

    fun onTitleChanged(value: String) = editDraft { it.copy(draftTitle = value) }
    fun onStartChanged(value: String) = editDraft { it.copy(draftStart = value) }
    fun onEndChanged(value: String) = editDraft { it.copy(draftEnd = value) }
    fun onDescriptionChanged(value: String) = editDraft { it.copy(draftDescription = value) }

    fun onSaveClicked() {
        val state = _uiState.value
        if (state.isSaving || state.isDeleting || state.isLoading) return

        val startsAt = ShanghaiTime.parseOrNull(state.draftStart)
        val endsAt = ShanghaiTime.parseOrNull(state.draftEnd)
        if (startsAt == null || endsAt == null) {
            _uiState.update {
                it.copy(errorMessage = "时间格式应为 yyyy-MM-dd HH:mm", savedMessage = null)
            }
            return
        }

        val base = editingEvent
        val candidate = (base ?: FixedEvent(id = "", title = "", startsAt = startsAt, endsAt = endsAt)).copy(
            title = state.draftTitle,
            startsAt = startsAt,
            endsAt = endsAt,
            description = state.draftDescription.ifBlank { null },
        )
        FixedEventForm.errorOrNull(candidate)?.let { error ->
            _uiState.update { it.copy(errorMessage = error, savedMessage = null) }
            return
        }

        _uiState.update { it.copy(isSaving = true, errorMessage = null, savedMessage = null) }
        val job = viewModelScope.launch {
            try {
                if (base == null) {
                    val saved = createFixedEvent(candidate)
                    _uiState.update {
                        it.copy(
                            draftTitle = "",
                            draftStart = "",
                            draftEnd = "",
                            draftDescription = "",
                            savedMessage = "已创建：${saved.title}",
                        )
                    }
                } else if (updateFixedEvent(candidate)) {
                    editingEvent = null
                    _uiState.update {
                        it.copy(
                            editingId = null,
                            draftTitle = "",
                            draftStart = "",
                            draftEnd = "",
                            draftDescription = "",
                            savedMessage = "已更新：${candidate.title.trim()}",
                        )
                    }
                } else {
                    _uiState.update { it.copy(errorMessage = "事件不存在或已被删除") }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(errorMessage = "保存失败，输入已保留。请重试。") }
            }
        }
        saveJob = job
        job.invokeOnCompletion {
            if (saveJob === job) _uiState.update { it.copy(isSaving = false) }
        }
    }

    /** 取消等待，不保证撤回已经提交的数据库写入。 */
    fun onCancelSaveClicked() {
        saveJob?.cancel()
    }

    fun onDeleteRequested(id: String) {
        val state = _uiState.value
        if (state.isSaving || state.isDeleting || state.pendingDeleteId != null) return
        _uiState.update { it.copy(pendingDeleteId = id, errorMessage = null, savedMessage = null) }
    }

    fun onDeleteCancelled() {
        if (_uiState.value.isDeleting) return
        _uiState.update { it.copy(pendingDeleteId = null) }
    }

    fun onDeleteConfirmed() {
        val state = _uiState.value
        val id = state.pendingDeleteId ?: return
        if (state.isDeleting) return

        val wasEditing = state.editingId == id
        _uiState.update { it.copy(isDeleting = true, errorMessage = null, savedMessage = null) }
        val job = viewModelScope.launch {
            try {
                val deleted = deleteFixedEvent(id)
                if (wasEditing) editingEvent = null
                _uiState.update {
                    it.copy(
                        pendingDeleteId = null,
                        editingId = if (wasEditing) null else it.editingId,
                        draftTitle = if (wasEditing) "" else it.draftTitle,
                        draftStart = if (wasEditing) "" else it.draftStart,
                        draftEnd = if (wasEditing) "" else it.draftEnd,
                        draftDescription = if (wasEditing) "" else it.draftDescription,
                        // 记录本来就不存在时按幂等处理，不提示错误。
                        savedMessage = if (deleted) "已删除" else null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(pendingDeleteId = null, errorMessage = "删除失败，请重试")
                }
            }
        }
        deleteJob = job
        job.invokeOnCompletion {
            if (deleteJob === job) _uiState.update { it.copy(isDeleting = false) }
        }
    }

    private fun loadEvents() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                refreshFixedEvents()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(errorMessage = "读取失败，请重试") }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private inline fun editDraft(block: (FixedEventEditorUiState) -> FixedEventEditorUiState) {
        if (_uiState.value.isSaving) return
        _uiState.update { block(it).copy(errorMessage = null, savedMessage = null) }
    }
}
