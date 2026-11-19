package com.kaixu.desktop.presentation.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kaixu.domain.usecase.scheduling.AgendaEntry
import com.kaixu.domain.usecase.scheduling.AgendaSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

private const val BoundaryTriggerSteps = 6f
private const val BoundaryMaxPullDp = 46f

private val BoundaryPromptStyle = TextStyle(
    fontSize = 12.sp,
    fontWeight = FontWeight.Medium,
)

internal data class AgendaJumpRequest(
    val id: Long,
    val targetIndex: Int,
)

@Composable
internal fun AgendaTimeline(
    snapshot: AgendaSnapshot,
    now: Long,
    onOpenEntry: (AgendaEntry) -> Unit,
    onOpenSchedule: (() -> Unit)?,
    modifier: Modifier = Modifier,
    jumpRequest: AgendaJumpRequest? = null,
    onFocusedKeyChanged: (String?) -> Unit = {},
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
    val boundaryImpulses = remember(snapshot.window.today) { Channel<Float>(Channel.UNLIMITED) }
    DisposableEffect(motionInputs, boundaryImpulses) {
        onDispose {
            motionInputs.close()
            boundaryImpulses.close()
        }
    }
    var boundaryPull by remember { mutableFloatStateOf(0f) }
    var boundaryPullOffsetPx by remember { mutableFloatStateOf(0f) }
    var lastBoundaryInput by remember { mutableLongStateOf(0L) }
    var leaving by remember { mutableStateOf(false) }
    val latestOnFocusedKeyChanged by rememberUpdatedState(onFocusedKeyChanged)

    LaunchedEffect(focusedKey) {
        latestOnFocusedKeyChanged(focusedKey)
    }

    LaunchedEffect(jumpRequest?.id) {
        val request = jumpRequest ?: return@LaunchedEffect
        val entries = currentEntries
        if (entries.isEmpty()) return@LaunchedEffect
        val target = request.targetIndex.coerceIn(0, entries.lastIndex)
        hoveredKey = null
        boundaryPull = 0f
        boundaryImpulses.trySend(0f)
        focusedKey = entries[target].key
        scrolling = true
        motionInputs.trySend(CardScrollInput(target, 0f))
    }

    BoxWithConstraints(modifier.testTag("home-agenda-viewport")) {
        val density = LocalDensity.current
        val viewportHeight = with(density) { maxHeight.toPx() }
        val sideZoneHeight = ((maxHeight - HomeAgendaStyle.CardHeight) / 2 - 10.dp).coerceAtLeast(0.dp)
        val cardHeight = with(density) { HomeAgendaStyle.CardHeight.toPx() }
        val cardGap = with(density) { HomeAgendaStyle.CardGap.toPx() }
        val maxPullPx = with(density) { BoundaryMaxPullDp.dp.toPx() }
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
        // 边界阻尼拉动与回弹循环：随累计滚动幅度增大拉动距离，松手后弹性回位。
        LaunchedEffect(entryKeys, maxPullPx) {
            while (boundaryImpulses.tryReceive().isSuccess) { /* clear stale impulses */ }
            boundaryPullOffsetPx = 0f
            val reboundMotion = BoundaryPullMotion()
            for (pullState in boundaryImpulses) {
                hoveredKey = null
                reboundMotion.impulse(pullState, maxPullPx)
                var previousFrame = withFrameNanos { it }
                while (reboundMotion.isRunning) {
                    val frame = withFrameNanos { it }
                    while (true) {
                        val next = boundaryImpulses.tryReceive().getOrNull() ?: break
                        reboundMotion.impulse(next, maxPullPx)
                    }
                    reboundMotion.advance((frame - previousFrame) / 1_000_000_000f)
                    previousFrame = frame
                    boundaryPullOffsetPx = reboundMotion.offsetPx
                }
                boundaryPullOffsetPx = 0f
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
                            // 使用指针事件自身的单调时间戳（生产环境与真实时钟一致，UI 测试中与虚拟测试时钟同步，不受截图落盘等宿主机耗时干扰）。
                            val moment = event.changes.firstOrNull()?.uptimeMillis ?: (System.nanoTime() / 1_000_000)
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
                                boundaryImpulses.trySend(0f)
                                continue
                            }
                            if (moment - lastBoundaryInput > 800 || sign(boundaryPull) != sign(steps.toFloat())) boundaryPull = 0f
                            lastBoundaryInput = moment
                            boundaryPull += steps
                            boundaryImpulses.trySend(boundaryPull)
                            if (openSchedule == null) continue
                            if (abs(boundaryPull) >= BoundaryTriggerSteps) {
                                leaving = true
                                scope.launch(Dispatchers.Main.immediate) { openSchedule?.invoke() }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (openSchedule != null && sideZoneHeight >= 72.dp) {
                val topPresence = (1f - scrollPosition / .85f).coerceIn(0f, 1f)
                if (topPresence > .01f) {
                    val activePull = if (boundaryPull < 0f) abs(boundaryPull) else 0f
                    val liveBounce = if (boundaryPullOffsetPx > 0f) (boundaryPullOffsetPx / maxPullPx).coerceIn(0f, 1f) else 0f
                    BoundaryOverscrollPrompt(
                        isTop = true,
                        presence = topPresence,
                        pullSteps = activePull,
                        liveBounce = liveBounce,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(sideZoneHeight)
                            .align(Alignment.TopCenter),
                    )
                }
                val lastIdx = snapshot.entries.lastIndex.toFloat()
                val bottomPresence = if (lastIdx >= 1f) (1f - (lastIdx - scrollPosition) / .85f).coerceIn(0f, 1f) else 0f
                if (bottomPresence > .01f) {
                    val activePull = if (boundaryPull > 0f) abs(boundaryPull) else 0f
                    val liveBounce = if (boundaryPullOffsetPx < 0f) (-boundaryPullOffsetPx / maxPullPx).coerceIn(0f, 1f) else 0f
                    BoundaryOverscrollPrompt(
                        isTop = false,
                        presence = bottomPresence,
                        pullSteps = activePull,
                        liveBounce = liveBounce,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(sideZoneHeight)
                            .align(Alignment.BottomCenter),
                    )
                }
            }

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
                            val slotDistance = abs(index - scrollPosition)
                            val pullDisplacement = boundaryPullOffsetPx * (1f + slotDistance * .14f)
                            val pullStretchY = (abs(boundaryPullOffsetPx) / cardHeight.coerceAtLeast(1f)) * .022f
                            transformOrigin = TransformOrigin(0f, .5f)
                            scaleX = pose.scale + if (index == arrivalIndex) arrivalStretch else 0f
                            scaleY = pose.scale * (1f + pullStretchY)
                            translationY = pose.offsetY + pullDisplacement
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
                            compositingStrategy = CompositingStrategy.Auto
                            shadowElevation = pose.elevation + hoverAmount * 3.dp.toPx()
                            shape = RoundedCornerShape(HomeAgendaStyle.CardRadius)
                            ambientShadowColor = HomeAgendaStyle.CardShadow.copy(alpha = .055f)
                            spotShadowColor = HomeAgendaStyle.CardShadow.copy(alpha = .115f)
                        })
                    }
                }
            }
        }
    }
}

/**
 * 首尾边界空白区的提示与可拉伸圆环状胶囊条：
 * - 静止时呈现一枚精致圆环（22×22 dp）与引导文案；
 * - 继续滑动时，文案与起点端帽保持稳固，胶囊单向沿滑动方向平滑拉长；
 * - 最大主圆点始终贴合拉伸前端端帽，后方按固定节距平滑浮现渐变尾迹圆点。
 */
@Composable
private fun BoundaryOverscrollPrompt(
    isTop: Boolean,
    presence: Float,
    pullSteps: Float,
    liveBounce: Float,
    modifier: Modifier = Modifier,
) {
    val stepFraction by animateFloatAsState(
        targetValue = (pullSteps / BoundaryTriggerSteps).coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = .88f, stiffness = 460f),
        label = "boundary-ring-progress",
    )
    val visualProgress = (stepFraction * .88f + liveBounce * .12f).coerceIn(0f, 1f)
    val ringWidth = 22.dp
    val activeAccent = HomeAgendaStyle.Upcoming
    val labelText = when {
        isTop && pullSteps > 0f -> "继续上滑展开完整日程"
        isTop -> "已至昨日起点  ·  继续上滑展开完整日程"
        pullSteps > 0f -> "继续下滑展开完整日程"
        else -> "已至明日末尾  ·  继续下滑展开完整日程"
    }
    val labelColor = lerp(HomeAgendaStyle.Muted, activeAccent, (stepFraction * .85f + liveBounce * .25f).coerceIn(0f, 1f))

    BoxWithConstraints(
        modifier = modifier
            .graphicsLayer {
                alpha = presence
                val shift = (1f - presence) * 10.dp.toPx() * if (isTop) -1f else 1f
                translationY = shift
            }
            .testTag(if (isTop) "home-boundary-top" else "home-boundary-bottom"),
        contentAlignment = Alignment.Center,
    ) {
        val maxRingHeight = (maxHeight - 36.dp).coerceIn(38.dp, 96.dp)
        val stretchRange = (maxRingHeight - ringWidth).coerceAtLeast(16.dp)
        val ringHeight = (ringWidth + stretchRange * visualProgress).coerceAtMost(maxRingHeight)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!isTop) {
                Text(
                    text = labelText,
                    style = BoundaryPromptStyle,
                    color = labelColor,
                )
            }
            // 固定轨道高度并单端锚定：胶囊仅向滑动方向延展，避免文案和起点随弹性拉动上下跳动。
            Box(
                modifier = Modifier
                    .width(ringWidth)
                    .height(maxRingHeight),
                contentAlignment = if (isTop) Alignment.BottomCenter else Alignment.TopCenter,
            ) {
                Canvas(
                    modifier = Modifier
                        .width(ringWidth)
                        .height(ringHeight)
                        .testTag(if (isTop) "home-boundary-ring-top" else "home-boundary-ring-bottom"),
                ) {
                    val strokeWidth = 1.5.dp.toPx()
                    val inset = strokeWidth / 2f
                    val w = (size.width - strokeWidth).coerceAtLeast(1f)
                    val h = (size.height - strokeWidth).coerceAtLeast(1f)
                    val radius = CornerRadius(w / 2f, w / 2f)
                    val topLeft = Offset(inset, inset)
                    val rectSize = Size(w, h)

                    // 灰白毛玻璃底面
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(HomeAgendaStyle.CardGlassTop, HomeAgendaStyle.CardGlassBottom),
                        ),
                        topLeft = topLeft,
                        size = rectSize,
                        cornerRadius = radius,
                    )
                    // 内壁高光环
                    val innerInset = strokeWidth + .8.dp.toPx()
                    val innerW = (size.width - innerInset * 2f).coerceAtLeast(1f)
                    val innerH = (size.height - innerInset * 2f).coerceAtLeast(1f)
                    if (innerW > 2f && innerH > 2f) {
                        drawRoundRect(
                            color = Color.White.copy(alpha = .88f),
                            topLeft = Offset(innerInset, innerInset),
                            size = Size(innerW, innerH),
                            cornerRadius = CornerRadius(innerW / 2f, innerW / 2f),
                            style = Stroke(width = .9.dp.toPx()),
                        )
                    }
                    // 基础冷银外环
                    drawRoundRect(
                        color = HomeAgendaStyle.CardBorder,
                        topLeft = topLeft,
                        size = rectSize,
                        cornerRadius = radius,
                        style = Stroke(width = strokeWidth),
                    )
                    // 随滚动加深的着色圆环描边
                    val activeAlpha = (.26f + .74f * visualProgress).coerceIn(0f, 1f)
                    drawRoundRect(
                        color = activeAccent.copy(alpha = activeAlpha),
                        topLeft = topLeft,
                        size = rectSize,
                        cornerRadius = radius,
                        style = Stroke(width = strokeWidth + .35.dp.toPx() * stepFraction),
                    )

                    val centerX = size.width / 2f
                    val capRadius = size.width / 2f
                    val originY = if (isTop) size.height - capRadius else capRadius
                    val tipY = if (isTop) capRadius else size.height - capRadius
                    val span = abs(tipY - originY)
                    val maxSpan = stretchRange.toPx().coerceAtLeast(1f)
                    val direction = if (isTop) -1f else 1f

                    // 连续尾迹点：按固定节距留驻在主圆点身后，随距离渐变收敛，彻底消除晃动感。
                    val dotSpacing = 7.5.dp.toPx()
                    val revealStart = 4.5.dp.toPx()
                    val revealSpan = 4.0.dp.toPx()
                    if (span > revealStart) {
                        var distance = 0f
                        while (distance < span - revealStart) {
                            val clearance = span - distance
                            val rawReveal = ((clearance - revealStart) / revealSpan).coerceIn(0f, 1f)
                            val reveal = rawReveal * rawReveal * (3f - 2f * rawReveal)
                            val proximity = (1f - clearance / maxSpan).coerceIn(0f, 1f)
                            val dotRadius = (1.30.dp.toPx() + .80.dp.toPx() * proximity) * reveal
                            val dotAlpha = (.26f + .48f * proximity) * reveal
                            if (dotRadius > .2f && dotAlpha > .01f) {
                                drawCircle(
                                    color = activeAccent.copy(alpha = dotAlpha),
                                    radius = dotRadius,
                                    center = Offset(centerX, originY + direction * distance),
                                )
                            }
                            distance += dotSpacing
                        }
                    }

                    // 牵引光晕与主圆点：始终贴合拉伸前端一侧的端帽中心。
                    val mainRadius = 3.2.dp.toPx() + .55.dp.toPx() * visualProgress
                    if (visualProgress > .02f) {
                        drawCircle(
                            color = activeAccent.copy(alpha = .15f * visualProgress.coerceIn(.25f, 1f)),
                            radius = 5.6.dp.toPx() + 1.0.dp.toPx() * visualProgress,
                            center = Offset(centerX, tipY),
                        )
                    }
                    drawCircle(
                        color = activeAccent,
                        radius = mainRadius,
                        center = Offset(centerX, tipY),
                    )
                }
            }
            if (isTop) {
                Text(
                    text = labelText,
                    style = BoundaryPromptStyle,
                    color = labelColor,
                )
            }
        }
    }
}

/**
 * 边界拉动与回弹物理模型：
 * 每次越界滚轮输入按累计幅度计算更大的目标拉伸距离（指数阻尼递增），
 * 先快速拉出，随后自动弹性回弹归零。
 */
internal class BoundaryPullMotion {
    var offsetPx = 0f
        private set
    private var velocity = 0f
    private var targetPeakPx = 0f
    private var pullTimer = 0f
    var isRunning = false
        private set

    fun impulse(boundaryPull: Float, maxPullPx: Float) {
        if (boundaryPull == 0f) {
            targetPeakPx = 0f
            pullTimer = PullHoldSeconds
            isRunning = abs(offsetPx) > .5f || abs(velocity) > 5f
            return
        }
        val level = abs(boundaryPull).coerceIn(1f, BoundaryTriggerSteps)
        val amplitude = maxPullPx * (1f - exp(-level * .38f))
        targetPeakPx = -sign(boundaryPull) * amplitude
        pullTimer = 0f
        isRunning = true
    }

    fun advance(seconds: Float) {
        var remaining = seconds.coerceIn(0f, .064f)
        while (remaining > 0f && isRunning) {
            val dt = min(remaining, 1f / 120f)
            advanceStep(dt)
            remaining -= dt
        }
    }

    private fun advanceStep(dt: Float) {
        pullTimer += dt
        val pulling = pullTimer < PullHoldSeconds
        val target = if (pulling) targetPeakPx else 0f
        val omega = if (pulling) 34f else 20f
        val damping = if (pulling) 1f else .82f
        val decay = exp(-damping * omega * dt)
        val diff = offsetPx - target
        if (damping < 1f) {
            val freq = omega * sqrt(1f - damping * damping)
            val c = cos(freq * dt)
            val s = sin(freq * dt)
            val coeff = (velocity + damping * omega * diff) / freq
            val disp = diff * c + coeff * s
            offsetPx = target + decay * disp
            velocity = decay * (-damping * omega * disp + freq * (coeff * c - diff * s))
        } else {
            val coeff = velocity + omega * diff
            offsetPx = target + (diff + coeff * dt) * decay
            velocity = (velocity - omega * coeff * dt) * decay
        }
        if (!pulling && abs(offsetPx) < .4f && abs(velocity) < 4f) {
            offsetPx = 0f
            velocity = 0f
            isRunning = false
        }
    }

    companion object {
        private const val PullHoldSeconds = .096f
    }
}
