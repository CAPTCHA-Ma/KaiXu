package com.kaixu.desktop.presentation.viewmodel.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaixu.domain.usecase.scheduling.AgendaSnapshot
import com.kaixu.domain.usecase.scheduling.AgendaUseCase
import com.kaixu.domain.usecase.scheduling.AgendaWindow
import com.kaixu.domain.validation.scheduling.ShanghaiTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val now: Long = System.currentTimeMillis(),
    val agenda: AgendaSnapshot? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

class HomeViewModel(
    private val agenda: AgendaUseCase,
    private val ownerUserId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeUiState(now = clock()))
    val uiState = mutableState.asStateFlow()
    private var load: Job? = null

    fun refresh() {
        load?.cancel()
        val now = clock()
        val window = AgendaWindow.around(ShanghaiTime.localDateOf(now))
        mutableState.update { it.copy(now = now, loading = true, error = null) }
        load = viewModelScope.launch {
            try {
                val snapshot = agenda(ownerUserId, window)
                mutableState.update { it.copy(agenda = snapshot, loading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, error = "日程读取失败，请重试。") }
            }
        }
    }

    fun tick() {
        val now = clock()
        if (mutableState.value.agenda?.window?.today != ShanghaiTime.localDateOf(now)) refresh()
        else mutableState.update { it.copy(now = now) }
    }
}
