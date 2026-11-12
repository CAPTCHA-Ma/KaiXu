package com.kaixu.desktop.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kaixu.desktop.presentation.navigation.DesktopDestination
import com.kaixu.desktop.presentation.theme.DesktopChromeColors as Colors

/** 窄版主导航，背景与标题栏连续，页面跳转由外部导航负责。 */
@Composable
fun DesktopSidebar(
    activeSection: String?,
    showSchedule: Boolean,
    onOpenSection: (String) -> Unit,
    modifier: Modifier = Modifier,
    onPreview: (source: String, title: String?) -> Unit = { _, _ -> },
) {
    val sections = buildList {
        add(DesktopDestination.TASK_EDITOR to "任务")
        if (showSchedule) add(DesktopDestination.SCHEDULE to "日程")
        add(DesktopDestination.WORKSPACES to "工作区")
    }
    Column(
        modifier.width(Colors.SidebarWidth).fillMaxHeight()
            .background(Colors.Color0)
            .testTag("desktop-sidebar")
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((route, label) in sections) {
            DesktopChromeButton(
                label = label,
                tag = "nav-$route",
                onClick = { onOpenSection(route) },
                modifier = Modifier.size(Colors.SidebarButtonSize),
                selected = activeSection == route,
                onPreview = onPreview,
            ) { foreground ->
                SidebarIcon(route, foreground)
            }
        }
    }
}

/** 与标题栏保持一致的线框图标；按钮语义仍由外层提供中文名称。 */
@Composable
private fun SidebarIcon(route: String, foreground: Color) {
    Canvas(Modifier.size(20.dp)) {
        val unit = size.minDimension / 24f
        val stroke = Stroke(1.8f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawLine(foreground, Offset(x1 * unit, y1 * unit), Offset(x2 * unit, y2 * unit), stroke.width, StrokeCap.Round)
        }
        when (route) {
            DesktopDestination.TASK_EDITOR -> {
                drawRoundRect(
                    foreground, Offset(3f * unit, 3f * unit), Size(18f * unit, 18f * unit),
                    CornerRadius(2f * unit), style = stroke,
                )
                for (y in listOf(9f, 15f)) {
                    val check = Path().apply {
                        moveTo(6f * unit, y * unit)
                        lineTo(8f * unit, (y + 2f) * unit)
                        lineTo(11f * unit, (y - 2f) * unit)
                    }
                    drawPath(check, foreground, style = stroke)
                    line(14f, y, 18f, y)
                }
            }
            DesktopDestination.SCHEDULE -> {
                drawRoundRect(
                    foreground, Offset(3f * unit, 5f * unit), Size(18f * unit, 16f * unit),
                    CornerRadius(2f * unit), style = stroke,
                )
                line(8f, 3f, 8f, 7f)
                line(16f, 3f, 16f, 7f)
                line(3f, 10f, 21f, 10f)
                for (x in listOf(8f, 12f, 16f)) {
                    drawCircle(foreground, .9f * unit, Offset(x * unit, 15f * unit))
                }
            }
            DesktopDestination.WORKSPACES -> {
                val folder = Path().apply {
                    moveTo(3f * unit, 6f * unit)
                    lineTo(9f * unit, 6f * unit)
                    lineTo(11f * unit, 8f * unit)
                    lineTo(21f * unit, 8f * unit)
                    lineTo(21f * unit, 20f * unit)
                    lineTo(3f * unit, 20f * unit)
                    close()
                }
                drawPath(folder, foreground, style = stroke)
                line(3f, 11f, 21f, 11f)
            }
        }
    }
}
