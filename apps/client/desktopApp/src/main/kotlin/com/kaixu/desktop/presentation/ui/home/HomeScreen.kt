package com.kaixu.desktop.presentation.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kaixu.desktop.presentation.viewmodel.home.HomeUiState
import com.kaixu.domain.usecase.scheduling.AgendaEntry

/** 第一轮视觉参数集中在这里，便于调整区域占比、卡片大小与圆角。 */
internal object HomeAgendaStyle {
    const val WidthFraction = .62f
    const val HeightFraction = .80f
    val CardRadius = 18.dp
    val CardHeight = 104.dp
    val CardGap = 14.dp
    const val HoverScaleIncrease = .018f
    val HoverHorizontalOffset = 6.dp
    val HoverVerticalOffset = 4.dp
    val Text = Color(0xFF243044)
    val Muted = Color(0xFF8A93A3)
}

@Composable
fun HomeScreen(
    state: HomeUiState = HomeUiState(loading = false),
    onOpenEntry: (AgendaEntry) -> Unit = {},
    onOpenSchedule: (() -> Unit)? = null,
    onRefresh: () -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 30.dp)
        .testTag("home-screen")) {
        val paneWidth = if (maxWidth < 720.dp) maxWidth else maxWidth * HomeAgendaStyle.WidthFraction
        // 矮窗口至少容纳中央三张完整卡片；正常尺寸上下各预留 10%。
        val paneHeight = (maxHeight * HomeAgendaStyle.HeightFraction)
            .coerceAtLeast(HomeAgendaStyle.CardHeight * 3 + HomeAgendaStyle.CardGap * 2)
            .coerceAtMost(maxHeight)
        Box(
            modifier = Modifier.width(paneWidth).height(paneHeight).align(Alignment.CenterStart)
                .testTag("home-agenda-pane"),
        ) {
            when {
                state.error != null -> HomeAgendaMessage(state.error, "重试", onRefresh)
                state.agenda == null && state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                state.agenda?.entries.isNullOrEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无日程", style = MaterialTheme.typography.bodyMedium, color = HomeAgendaStyle.Muted)
                }
                else -> AgendaTimeline(
                    snapshot = requireNotNull(state.agenda), now = state.now,
                    onOpenEntry = onOpenEntry, onOpenSchedule = onOpenSchedule,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun HomeAgendaMessage(message: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = MaterialTheme.typography.titleMedium, color = HomeAgendaStyle.Text)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onAction, modifier = Modifier.testTag("home-refresh")) { Text(action) }
    }
}
