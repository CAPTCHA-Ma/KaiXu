package com.kaixu.desktop.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kaixu.desktop.presentation.theme.DesktopChromeColors as Colors
import com.kaixu.desktop.window.WindowsTitleBarState
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun DesktopTitleBar(
    host: WindowsTitleBarState,
    settingsSelected: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    currentTitle: String,
    previewTitle: String?,
    backTitle: String?,
    forwardTitle: String?,
    onPreview: (source: String, title: String?) -> Unit,
) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(Colors.Height).background(Colors.Color0)
            .testTag("desktop-titlebar")
            .pointerInput(host) {
                awaitPointerEventScope {
                    while (true) host.hitTest(awaitPointerEvent(PointerEventPass.Initial))
                }
            },
    ) {
        // 两边留相等空间，让标题相对整个窗口居中，并避让原生窗口按钮。
        val safeMargin = maxOf(host.leftInset + 8.dp + Colors.ButtonHeight * 3 + 12.dp, host.rightInset + 8.dp)
        val captionWidth = (maxWidth - safeMargin * 2).coerceIn(0.dp, 220.dp)
        DesktopTitleCaption(currentTitle, previewTitle, Modifier.width(captionWidth).align(Alignment.Center))
        Row(
            Modifier.align(Alignment.CenterStart).padding(start = host.leftInset + 8.dp)
                .onGloballyPositioned { host.updateButtonsBounds(it.boundsInRoot()) },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TitleBarButton("设置", "titlebar-settings", Glyph.Settings, selected = settingsSelected,
                onClick = onSettings, onPreview = onPreview)
            TitleBarButton("返回", "titlebar-back", Glyph.Back, enabled = canGoBack,
                onClick = onBack, previewLabel = backTitle ?: "返回", onPreview = onPreview)
            TitleBarButton("前进", "titlebar-forward", Glyph.Forward, enabled = canGoForward,
                onClick = onForward, previewLabel = forwardTitle ?: "前进", onPreview = onPreview)
        }
    }
}

private enum class Glyph { Settings, Back, Forward }

@Composable
private fun TitleBarButton(
    label: String,
    tag: String,
    glyph: Glyph,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    previewLabel: String = label,
    onPreview: (source: String, title: String?) -> Unit,
) {
    DesktopChromeButton(
        label = label,
        tag = tag,
        onClick = onClick,
        modifier = Modifier.size(Colors.ButtonHeight),
        selected = selected,
        enabled = enabled,
        previewLabel = previewLabel,
        onPreview = onPreview,
    ) { foreground ->
        Canvas(Modifier.size(18.dp)) {
            val unit = size.minDimension / 24f
            val stroke = Stroke(1.8f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
            if (glyph == Glyph.Settings) {
                val gear = Path()
                repeat(64) { index ->
                    val angle = index * Math.PI * 2 / 64 - Math.PI / 2
                    val radius = if (index % 8 in 2..5) 10f else 7.8f
                    val x = (12f + cos(angle).toFloat() * radius) * unit
                    val y = (12f + sin(angle).toFloat() * radius) * unit
                    if (index == 0) gear.moveTo(x, y) else gear.lineTo(x, y)
                }
                gear.close()
                drawPath(gear, foreground, style = stroke)
                drawCircle(foreground, 3.2f * unit, center, style = stroke)
            } else {
                fun point(x: Float, y: Float) = Offset((if (glyph == Glyph.Forward) 24f - x else x) * unit, y * unit)
                drawLine(foreground, point(5f, 12f), point(19f, 12f), stroke.width, StrokeCap.Round)
                val arrow = Path().apply {
                    val a = point(11f, 6f); val b = point(5f, 12f); val c = point(11f, 18f)
                    moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y)
                }
                drawPath(arrow, foreground, style = stroke)
            }
        }
    }
}
