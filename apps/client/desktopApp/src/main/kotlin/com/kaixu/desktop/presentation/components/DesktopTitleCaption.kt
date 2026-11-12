package com.kaixu.desktop.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaixu.desktop.presentation.theme.DesktopChromeColors as Colors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val HoverExitGraceMillis = 180L
private const val PreviewRollDurationMillis = 220
private const val PreviewProgress = .72f

private class CaptionPreviewLayer(val title: String, fromAbove: Boolean) {
    val offset = Animatable(if (fromAbove) -1f else 0f)
    val alpha = Animatable(if (fromAbove) 0f else 1f)
}

/** 同一段位移动画可暂停预览、反向退出，或在导航后继续到完全替换。 */
@Composable
internal fun DesktopTitleCaption(
    currentTitle: String,
    previewTitle: String?,
    modifier: Modifier = Modifier,
) {
    var restingTitle by remember { mutableStateOf(currentTitle) }
    val previewLayers = remember { mutableStateListOf<CaptionPreviewLayer>() }
    var activeLayer by remember { mutableStateOf<CaptionPreviewLayer?>(null) }
    val progress = remember { Animatable(0f) }
    val preview = previewTitle?.takeUnless { it == currentTitle }
    LaunchedEffect(currentTitle, preview) {
        val committing = currentTitle != restingTitle
        val target = if (committing) currentTitle else preview
        if (target == null) {
            // 跨越按钮间的空白时保留预览，新目标会取消这次延迟及回弹。
            if (activeLayer != null) delay(HoverExitGraceMillis)
            progress.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
            activeLayer = null
            previewLayers.clear()
        } else {
            if (activeLayer?.title != target) {
                val layer = CaptionPreviewLayer(target, fromAbove = activeLayer != null && progress.value > 0f)
                previewLayers.add(layer)
                activeLayer = layer
            }
            progress.animateTo(if (committing) 1f else PreviewProgress, tween(300, easing = FastOutSlowInEasing))
            if (committing) {
                restingTitle = target
                activeLayer = null
                previewLayers.clear()
                progress.snapTo(0f)
                if (preview != null && preview != target) {
                    val layer = CaptionPreviewLayer(preview, fromAbove = false)
                    previewLayers.add(layer)
                    activeLayer = layer
                    progress.animateTo(PreviewProgress, tween(300, easing = FastOutSlowInEasing))
                }
            }
        }
    }

    // 各文字层独立完成进出；再次换目标时已有退场动画继续，避免半途跳回起点。
    previewLayers.forEach { layer ->
        key(layer) {
            val active = layer === activeLayer
            LaunchedEffect(active) {
                coroutineScope {
                    launch {
                        layer.offset.animateTo(if (active) 0f else 1f,
                            tween(PreviewRollDurationMillis, easing = FastOutSlowInEasing))
                    }
                    launch {
                        layer.alpha.animateTo(if (active) 1f else 0f,
                            tween(PreviewRollDurationMillis, easing = FastOutSlowInEasing))
                    }
                }
                if (!active) previewLayers.remove(layer)
            }
        }
    }

    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
    BoxWithConstraints(
        modifier.height(32.dp).testTag("titlebar-caption")
            .semantics {
                contentDescription = currentTitle
                stateDescription = preview?.let { "预览：$it" } ?: "当前页面"
            },
    ) {
        val textLimit = with(density) { (maxWidth - 8.dp).coerceIn(0.dp, 160.dp).roundToPx() }
        val outgoing = measurer.measure(restingTitle, style, overflow = TextOverflow.Ellipsis, maxLines = 1,
            constraints = Constraints(maxWidth = textLimit))
        val incoming = previewLayers.map { layer ->
            layer to measurer.measure(layer.title, style, overflow = TextOverflow.Ellipsis, maxLines = 1,
                constraints = Constraints(maxWidth = textLimit))
        }
        val widestIncoming = incoming.maxOfOrNull { it.second.size.width } ?: outgoing.size.width
        val incomingWidth by animateFloatAsState(widestIncoming.toFloat(),
            tween(PreviewRollDurationMillis, easing = FastOutSlowInEasing))
        Canvas(Modifier.fillMaxSize()) {
            val p = progress.value
            val previewProgress = (p / PreviewProgress).coerceIn(0f, 1f)
            val remainingAlpha = ((1f - p) / (1f - PreviewProgress)).coerceIn(0f, 1f)
            val gap = 8.dp.toPx()
            val halfWidths = (outgoing.size.width + incomingWidth) / 2f
            // 为整段新标题预留空间，不再用两段文字共用的窄窗口截断右侧文字。
            val incomingTravel = (halfWidths + gap)
                .coerceAtMost(((size.width - widestIncoming) / 2f - 2.dp.toPx()).coerceAtLeast(0f))
            val outgoingTravel = (halfWidths + gap - incomingTravel * (1f - PreviewProgress)) / PreviewProgress
            clipRect {
                withTransform({
                    translate((size.width - outgoing.size.width) / 2f - outgoingTravel * p,
                        (size.height - outgoing.size.height) / 2f)
                }) {
                    // 渐变仅作用于旧文字：左端先淡出，向右逐渐清晰，没有遮罩硬边或背景阴影。
                    drawText(outgoing, brush = Brush.horizontalGradient(
                        0f to Colors.Color3.copy(alpha = (1f - previewProgress) * remainingAlpha),
                        .45f to Colors.Color3.copy(alpha = (1f - .92f * previewProgress) * remainingAlpha),
                        1f to Colors.Color3.copy(alpha = (1f - .35f * previewProgress) * remainingAlpha),
                        startX = 0f, endX = outgoing.size.width.toFloat().coerceAtLeast(1f),
                    ), topLeft = Offset.Zero)
                }
                incoming.forEach { (layer, text) ->
                    val separation = outgoingTravel * p + incomingTravel * (1f - p)
                    val clearSpace = ((separation - halfWidths) / gap).coerceIn(0f, 1f)
                    drawText(text, color = Colors.Color3.copy(
                        alpha = minOf((p / .3f).coerceIn(0f, 1f), clearSpace) * layer.alpha.value),
                        topLeft = Offset((size.width - text.size.width) / 2f + incomingTravel * (1f - p),
                            (size.height - text.size.height) / 2f + 22.dp.toPx() * layer.offset.value))
                }
            }
        }
    }
}
