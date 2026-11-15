package com.kaixu.desktop.presentation.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.kaixu.desktop.presentation.ui.records.LocalRecordTarget
import com.kaixu.desktop.presentation.ui.records.RecordKind
import com.kaixu.desktop.presentation.viewmodel.home.HomeViewModel
import com.kaixu.domain.usecase.scheduling.AgendaKind
import kotlinx.coroutines.delay

@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    onOpenRecord: (LocalRecordTarget) -> Unit,
    onOpenSchedule: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, viewModel) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refresh()
            while (true) {
                delay(30_000)
                viewModel.tick()
            }
        }
    }
    HomeScreen(state, onRefresh = viewModel::refresh, onOpenSchedule = onOpenSchedule, onOpenEntry = { entry ->
        onOpenRecord(LocalRecordTarget(when (entry.kind) {
            AgendaKind.TASK_DEADLINE -> RecordKind.TASK
            AgendaKind.FIXED_EVENT -> RecordKind.EVENT
            AgendaKind.WORK_SESSION -> RecordKind.SESSION
        }, entry.recordId))
    })
}
