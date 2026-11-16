package com.kaixu.desktop.presentation.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kaixu.domain.usecase.scheduling.AgendaEntry
import com.kaixu.domain.usecase.scheduling.AgendaSnapshot
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.roundToInt

@Composable
internal fun AgendaTimeline(
    snapshot: AgendaSnapshot,
    now: Long,
    onOpenEntry: (AgendaEntry) -> Unit,
    onOpenSchedule: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val openSchedule by rememberUpdatedState(onOpenSchedule)
    var focusedKey by rememberSaveable(snapshot.window.today) { mutableStateOf<String?>(null) }
    var scrollPosition by remember(snapshot.window.today) {
        mutableFloatStateOf(snapshot.entries.indexOfFirst { it.key == focusedKey }.takeIf { it >= 0 }?.toFloat()
            ?: (snapshot.nearestIndex(now) ?: 0).toFloat())
    }
    val currentEntries by rememberUpdatedState(snapshot.entries)
    var arrivalStretch by remember { mutableFloatStateOf(0f) }
    var arrivalIndex by remember { mutableIntStateOf(-1) }
    var scrolling by remember { mutableStateOf(false) }
    var hoveredKey by remember { mutableStateOf<String?>(null) }
    var pointerBias by remember { mutableStateOf(Offset.Zero) }
    val motionInputs = remember(snapshot.window.today) { Channel<CardScrollInput>(Channel.UNLIMITED) }
    DisposableEffect(motionInputs) { onDispose { motionInputs.close() } }
    var boundaryPull by remember { mutableFloatStateOf(0f) }
    var lastBoundaryInput by remember { mutableLongStateOf(0L) }
    var leaving by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier.testTag("home-agenda-viewport")) {
        val density = LocalDensity.current
        val viewportHeight = with(density) { maxHeight.toPx() }
        val cardHeight = with(density) { HomeAgendaStyle.CardHeight.toPx() }
        val cardGap = with(density) { HomeAgendaStyle.CardGap.toPx() }
        val entryKeys = snapshot.entries.map { it.key }
        LaunchedEffect(entryKeys, maxHeight, cardHeight) {
            val initialIndex = entryKeys.indexOf(focusedKey).takeIf { it >= 0 } ?: (snapshot.nearestIndex(now) ?: 0)
            focusedKey = entryKeys[initialIndex]
            val motion = CardScrollMotion(initialIndex, entryKeys.lastIndex)
            // 数据结构或尺寸变化时，以保存的目标为准，不重放旧布局中积压的索引。
            while (motionInputs.tryReceive().isSuccess) { /* discard obsolete layout input */ }
            scrollPosition = initialIndex.toFloat()
            arrivalStretch = 0f
            arrivalIndex = initialIndex
            scrolling = false
            hoveredKey = null
            // 一个帧循环消费整段滚动；新的滚轮事件延长目标，沿用当前速度。
            for (input in motionInputs) {
                scrolling = true
                hoveredKey = null
                motion.input(input.index, input.remainder)
                var previousFrame = withFrameNanos { it }
                while (motion.isRunning) {
                    val frame = withFrameNanos { it }
                    while (true) {
                        val next = motionInputs.tryReceive().getOrNull() ?: break
                        motion.input(next.index, next.remainder)
                    }
                    motion.advance((frame - previousFrame) / 1_000_000_000f)
                    previousFrame = frame
                    scrollPosition = motion.position
                    arrivalStretch = motion.arrivalStretch
                    arrivalIndex = motion.targetIndex
                }
                scrolling = false
            }
        }
        Box(
            modifier = Modifier.fillMaxSize().testTag("home-agenda-list")
                .drawWithContent {
                    // 宽卡片放大后的溢出随宽度增长；横向容纳放大、跟随位移与阴影，纵向仍严格裁剪。
                    val horizontalBleed = size.width * HomeAgendaStyle.HoverScaleIncrease / 2f +
                        HomeAgendaStyle.HoverHorizontalOffset.toPx() + 16.dp.toPx()
                    clipRect(left = -horizontalBleed, top = 0f, right = size.width + horizontalBleed, bottom = size.height) {
                        this@drawWithContent.drawContent()
                    }
                }
                .pointerInput(snapshot.window.today) {
                    // 一格输入对应一张卡片；小数输入先阻尼预移，达到阈值后提交整卡目标。
                    var wheelRemainder = 0f
                    var lastWheelInput = 0L
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type != PointerEventType.Scroll || focusedKey == null || leaving) continue
                            // 绘制裁剪不参与命中测试，预留区域里的不可见卡片不能截获滚轮。
                            if (event.changes.none { it.position.y >= 0f && it.position.y < size.height }) continue
                            val delta = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                            if (delta == 0f) continue
                            event.changes.forEach { it.consume() }
                            hoveredKey = null
                            val moment = System.nanoTime() / 1_000_000
                            if (moment - lastWheelInput > CardScrollMotion.InputGraceSeconds * 1_000 || sign(wheelRemainder) != sign(delta)) wheelRemainder = 0f
                            lastWheelInput = moment
                            wheelRemainder += delta
                            val steps = wheelRemainder.toInt()
                            wheelRemainder -= steps
                            val index = currentEntries.indexOfFirst { it.key == focusedKey }
                            if (index < 0) continue
                            if (steps == 0) {
                                scrolling = true
                                motionInputs.trySend(CardScrollInput(index, wheelRemainder))
                                continue
                            }
                            val target = (index + steps).coerceIn(0, currentEntries.lastIndex)
                            if (target != index) {
                                scrolling = true
                                focusedKey = currentEntries[target].key
                                motionInputs.trySend(CardScrollInput(target, wheelRemainder))
                                boundaryPull = 0f
                                continue
                            }
                            if (openSchedule == null) continue
                            if (moment - lastBoundaryInput > 800 || sign(boundaryPull) != sign(steps.toFloat())) boundaryPull = 0f
                            lastBoundaryInput = moment
                            boundaryPull += steps
                            if (abs(boundaryPull) >= 6f) {
                                leaving = true
                                scope.launch(Dispatchers.Main.immediate) { openSchedule?.invoke() }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val visibleCenter by remember { derivedStateOf { scrollPosition.roundToInt() } }
            // 高窗口可以展示更多槽位，位置仍由固定卡片高度与间距决定。
            val visibleIndices = (visibleCenter - 5).coerceAtLeast(0)..(visibleCenter + 5).coerceAtMost(snapshot.entries.lastIndex)
            for (index in visibleIndices) {
                val entry = snapshot.entries[index]
                key(entry.key) {
                    val hovered = hoveredKey == entry.key && !scrolling
                    val hoverAmount by animateFloatAsState(
                        if (hovered) 1f else 0f,
                        spring(dampingRatio = 1f, stiffness = 280f), label = "agenda-hover-scale",
                    )
                    val hoverBias by animateOffsetAsState(
                        if (hovered) pointerBias else Offset.Zero,
                        spring(dampingRatio = 1f, stiffness = 240f), label = "agenda-hover-follow",
                    )
                    // 外层保留滚轮槽位与命中区域，内层单独做悬停变换，避免卡片追随鼠标时反复进出。
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(HomeAgendaStyle.CardHeight)
                        .zIndex(if (hovered) 20f else 10f - abs(index - visibleCenter))
                        .graphicsLayer {
                            val pose = wheelCardPose(index - scrollPosition, viewportHeight, cardHeight, cardGap)
                            transformOrigin = TransformOrigin(0f, .5f)
                            scaleX = pose.scale + if (index == arrivalIndex) arrivalStretch else 0f
                            scaleY = pose.scale
                            translationY = pose.offsetY
                        }
                        .pointerInput(entry.key, index, viewportHeight, cardHeight, cardGap) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    when (event.type) {
                                        PointerEventType.Enter, PointerEventType.Move -> {
                                            if (!scrolling && !leaving &&
                                                wheelCardPose(index - scrollPosition, viewportHeight, cardHeight, cardGap).alpha > .05f) {
                                                val point = event.changes.firstOrNull()?.position ?: continue
                                                hoveredKey = entry.key
                                                pointerBias = Offset(
                                                    (point.x / size.width.coerceAtLeast(1) * 2f - 1f).coerceIn(-1f, 1f),
                                                    (point.y / size.height.coerceAtLeast(1) * 2f - 1f).coerceIn(-1f, 1f),
                                                )
                                            }
                                        }
                                        PointerEventType.Exit -> if (hoveredKey == entry.key) hoveredKey = null
                                    }
                                }
                            }
                        },
                    ) {
                        AgendaCard(entry, now, snapshot.window.today, onOpen = {
                            if (wheelCardPose(index - scrollPosition, viewportHeight, cardHeight, cardGap).alpha > .05f) {
                                scope.launch(Dispatchers.Main.immediate) { onOpenEntry(it) }
                            }
                        }, modifier = Modifier.fillMaxSize().graphicsLayer {
                            val pose = wheelCardPose(index - scrollPosition, viewportHeight, cardHeight, cardGap)
                            scaleX = 1f + hoverAmount * HomeAgendaStyle.HoverScaleIncrease
                            scaleY = scaleX
                            translationX = hoverBias.x * HomeAgendaStyle.HoverHorizontalOffset.toPx()
                            translationY = hoverBias.y * HomeAgendaStyle.HoverVerticalOffset.toPx()
                            alpha = pose.alpha
                            compositingStrategy = CompositingStrategy.Offscreen
                            shadowElevation = pose.elevation + hoverAmount * 3.dp.toPx()
                            shape = RoundedCornerShape(HomeAgendaStyle.CardRadius)
                            ambientShadowColor = HomeAgendaStyle.CardShadow.copy(alpha = .06f)
                            spotShadowColor = HomeAgendaStyle.CardShadow.copy(alpha = .11f)
                        })
                    }
                }
            }
        }
    }
}
