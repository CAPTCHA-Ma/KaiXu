package com.kaixu.desktop.presentation.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaixu.desktop.presentation.theme.DesktopChromeColors
import com.kaixu.desktop.presentation.viewmodel.home.HomeQuickCreateOutcome
import com.kaixu.desktop.presentation.viewmodel.home.HomeQuickCreateRequest
import com.kaixu.desktop.presentation.viewmodel.home.HomeUiState
import com.kaixu.domain.usecase.scheduling.AgendaEntry
import com.kaixu.domain.usecase.scheduling.AgendaKind
import com.kaixu.domain.usecase.scheduling.AgendaSnapshot
import com.kaixu.domain.usecase.scheduling.FixedEventConfirmation
import com.kaixu.domain.validation.scheduling.ShanghaiTime

/** 视觉与布局参数集中在这里，便于调整分栏阈值、区域占比、卡片大小与配色。 */
internal object HomeAgendaStyle {
    const val WidthFraction = .62f
    const val HeightFraction = .80f
    val SplitThreshold = 720.dp
    val PanelGap = 10.dp
    val PanelHorizontalPadding = 22.dp
    val CardRadius = 32.dp
    val CardHeight = 104.dp
    val CardGap = 14.dp
    const val HoverScaleIncrease = .018f
    val HoverHorizontalOffset = 6.dp
    val HoverVerticalOffset = 4.dp
    val Text = Color(0xFF1A202C)
    val Muted = Color(0xFF666F7C)

    /** 极浅扁平毛玻璃卡面：近乎纯白背景色，依靠冷银外细边、纯白内高光与柔和投影与背景自然分层。 */
    val CardSurface = Color(0xFFF8F9FB)
    val CardGlassTop = Color(0xFFFAFBFD)
    val CardGlassBottom = Color(0xFFF6F7F9)
    val CardBorder = Color(0xFFE0E3EA)
    val CardBorderTop = Color(0xFFE7E9EF)
    val CardBorderBottom = Color(0xFFD9DDE6)
    val CardInnerRefraction = Color(0xFFDCE0E8).copy(alpha = .22f)
    val CardShadow = Color(0xFF1E293B)
    val TitleFaded = Color(0xFF606875)
    /** 尚未开始：松石墨青。 */
    val Upcoming = Color(0xFF28555C)
    /** 正在进行：朱砂。 */
    val Ongoing = Color(0xFFC44B32)
    /** 已经过去：冷灰雾银，文字用深一档保证可读。 */
    val Past = Color(0xFF9CA3AF)
    val PastText = Color(0xFF6B7280)
}

private val HeaderDateStyle = TextStyle(
    fontSize = 22.sp,
    fontWeight = FontWeight.Bold,
    fontFeatureSettings = "tnum",
)
private val HeaderWeekdayStyle = TextStyle(
    fontSize = 14.sp,
    fontWeight = FontWeight.Medium,
)
private val HeaderWeekChipStyle = TextStyle(
    fontSize = 11.5.sp,
    fontWeight = FontWeight.Medium,
    fontFeatureSettings = "tnum",
)
private val HeaderSummaryStyle = TextStyle(
    fontSize = 13.sp,
    fontWeight = FontWeight.Medium,
    fontFeatureSettings = "tnum",
)
private val AnchorTabStyle = TextStyle(
    fontSize = 12.sp,
    fontWeight = FontWeight.Medium,
)

@Composable
fun HomeScreen(
    state: HomeUiState = HomeUiState(loading = false),
    onOpenEntry: (AgendaEntry) -> Unit = {},
    onOpenSchedule: (() -> Unit)? = null,
    onRefresh: () -> Unit = {},
    onQuickCreate: (suspend (HomeQuickCreateRequest, FixedEventConfirmation?) -> HomeQuickCreateOutcome)? = null,
) {
    val focusManager = LocalFocusManager.current
    val today = state.agenda?.window?.today ?: ShanghaiTime.localDateOf(state.now)
    val yesterday = remember(today) { ShanghaiTime.plusDays(today, -1) }
    val tomorrow = remember(today) { ShanghaiTime.plusDays(today, 1) }

    var focusedKey by remember(today) { mutableStateOf<String?>(null) }
    var jumpSeq by remember { mutableLongStateOf(0L) }
    var jumpRequest by remember { mutableStateOf<AgendaJumpRequest?>(null) }

    val snapshot = state.agenda
    val nearestIndex = remember(snapshot, state.now) { snapshot?.nearestIndex(state.now) }
    val nearestKey = nearestIndex?.let { snapshot?.entries?.getOrNull(it)?.key }
    val focusedEntry = remember(snapshot, focusedKey, nearestIndex) {
        snapshot?.entries?.firstOrNull { it.key == focusedKey }
            ?: nearestIndex?.let { snapshot?.entries?.getOrNull(it) }
    }
    val activeDayOffset = remember(focusedEntry, today, yesterday, tomorrow) {
        when (focusedEntry?.let { ShanghaiTime.localDateOf(it.time) }) {
            yesterday -> -1
            tomorrow -> 1
            else -> 0
        }
    }
    val isAwayFromNow = nearestKey != null && focusedEntry?.key != null && focusedEntry.key != nearestKey
    val dayHasEntries = remember(snapshot, today, yesterday, tomorrow) {
        val entries = snapshot?.entries.orEmpty()
        mapOf(
            -1 to entries.any { ShanghaiTime.localDateOf(it.time) == yesterday },
            0 to entries.any { ShanghaiTime.localDateOf(it.time) == today },
            1 to entries.any { ShanghaiTime.localDateOf(it.time) == tomorrow },
        )
    }

    fun requestJumpToIndex(targetIndex: Int) {
        jumpSeq += 1L
        jumpRequest = AgendaJumpRequest(jumpSeq, targetIndex)
    }

    fun requestJumpToDay(offset: Int) {
        val entries = snapshot?.entries ?: return
        val targetDate = when (offset) {
            -1 -> yesterday
            1 -> tomorrow
            else -> today
        }
        val targetIndex = if (offset == 0 && nearestIndex != null &&
            entries.getOrNull(nearestIndex)?.let { ShanghaiTime.localDateOf(it.time) == today } == true
        ) {
            nearestIndex
        } else {
            entries.indexOfFirst { ShanghaiTime.localDateOf(it.time) == targetDate }
        }
        if (targetIndex >= 0) requestJumpToIndex(targetIndex)
    }

    fun requestJumpToCurrent() {
        nearestIndex?.let(::requestJumpToIndex)
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(DesktopChromeColors.Color0).testTag("home-screen"),
    ) {
        val isSplit = maxWidth >= HomeAgendaStyle.SplitThreshold
        val splitProgress by animateFloatAsState(
            targetValue = if (isSplit) 1f else 0f,
            animationSpec = spring(dampingRatio = .82f, stiffness = 240f),
            label = "home-split-progress",
        )
        val targetLeftWidth = (maxWidth - HomeAgendaStyle.PanelGap) * HomeAgendaStyle.WidthFraction
        val leftPanelWidth = (maxWidth - (maxWidth - targetLeftWidth) * splitProgress)
            .coerceIn(0.dp, maxWidth)
        val rightPanelWidth = (maxWidth - leftPanelWidth - HomeAgendaStyle.PanelGap)
            .coerceAtLeast(0.dp)
        // 矮窗口至少容纳中央三张完整卡片；正常尺寸上下各预留 10%。
        val paneHeight = (maxHeight * HomeAgendaStyle.HeightFraction)
            .coerceAtLeast(HomeAgendaStyle.CardHeight * 3 + HomeAgendaStyle.CardGap * 2)
            .coerceAtMost(maxHeight)
        val reservedEdgeHeight = ((maxHeight - paneHeight) / 2).coerceAtLeast(0.dp)

        HomePanelSurface(
            modifier = Modifier
                .width(leftPanelWidth)
                .fillMaxHeight()
                .align(Alignment.CenterStart)
                .pointerInput(focusManager) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            if (event.type == PointerEventType.Press && event.changes.none { it.isConsumed }) {
                                focusManager.clearFocus()
                            }
                        }
                    }
                }
                .testTag("home-left-panel"),
        ) {
            if (reservedEdgeHeight >= 36.dp) {
                HomeTodayHeader(
                    state = state,
                    today = today,
                    activeDayOffset = activeDayOffset,
                    dayHasEntries = dayHasEntries,
                    showJumpToCurrent = isAwayFromNow,
                    onSelectDay = ::requestJumpToDay,
                    onJumpToCurrent = ::requestJumpToCurrent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(reservedEdgeHeight)
                        .align(Alignment.TopCenter)
                        .padding(horizontal = HomeAgendaStyle.PanelHorizontalPadding + 14.dp)
                        .padding(top = 6.dp, bottom = 12.dp)
                        .testTag("home-today-header"),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(paneHeight)
                    .align(Alignment.Center)
                    .padding(horizontal = HomeAgendaStyle.PanelHorizontalPadding)
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
                        snapshot = requireNotNull(state.agenda),
                        now = state.now,
                        onOpenEntry = onOpenEntry,
                        onOpenSchedule = onOpenSchedule,
                        jumpRequest = jumpRequest,
                        onFocusedKeyChanged = { focusedKey = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (reservedEdgeHeight >= 48.dp) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(reservedEdgeHeight)
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = HomeAgendaStyle.PanelHorizontalPadding + 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    HomeQuickCaptureBar(
                        now = state.now,
                        today = today,
                        activeWorkspaces = state.activeWorkspaces,
                        onQuickCreate = onQuickCreate,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (isSplit || splitProgress > .005f) {
            val clamped = splitProgress.coerceIn(0f, 1f)
            val rightAlpha = (1f - (1f - clamped) * (1f - clamped)).coerceIn(0f, 1f)
            val rightScaleY = .96f + .04f * clamped
            HomePanelSurface(
                modifier = Modifier
                    .width(rightPanelWidth)
                    .fillMaxHeight()
                    .align(Alignment.CenterEnd)
                    .graphicsLayer {
                        alpha = rightAlpha
                        scaleY = rightScaleY
                        translationX = (1f - clamped) * 14.dp.toPx()
                        transformOrigin = TransformOrigin(1f, .5f)
                    }
                    .testTag("home-secondary-pane"),
            ) {}
        }
    }
}

@Composable
private fun HomePanelSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val panelShape = RoundedCornerShape(DesktopChromeColors.ContentCornerRadius)
    Surface(
        modifier = modifier.shadow(
            elevation = DesktopChromeColors.ContentElevation,
            shape = panelShape,
            clip = false,
            ambientColor = DesktopChromeColors.ContentAmbientShadow,
            spotColor = DesktopChromeColors.ContentSpotShadow,
        ),
        shape = panelShape,
        color = DesktopChromeColors.ContentBackground,
        border = BorderStroke(1.dp, DesktopChromeColors.ContentBorder),
    ) {
        Box(Modifier.fillMaxSize(), content = content)
    }
}

/**
 * 瀑布流顶部今日信息栏：
 * - 左上角：今日公历日期、星期与预留的教学周次插槽；
 * - 中段：三日交互锚点（昨天 / 今天 / 明天）及滚离当前事项时浮现的「回到此刻」；
 * - 右上角：当日剩余事项与今日待交截止状态。
 */
@Composable
private fun HomeTodayHeader(
    state: HomeUiState,
    today: String,
    activeDayOffset: Int,
    dayHasEntries: Map<Int, Boolean>,
    showJumpToCurrent: Boolean,
    onSelectDay: (Int) -> Unit,
    onJumpToCurrent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val month = today.substring(5, 7).toIntOrNull() ?: 1
    val day = today.substring(8, 10).toIntOrNull() ?: 1
    val dateLabel = "${month}月${day}日"
    val weekdayLabel = when (ShanghaiTime.isoWeekday(today)) {
        1 -> "星期一"
        2 -> "星期二"
        3 -> "星期三"
        4 -> "星期四"
        5 -> "星期五"
        6 -> "星期六"
        7 -> "星期日"
        else -> ""
    }
    val summary = rememberTodaySummary(state.agenda, today, state.now)

    BoxWithConstraints(modifier = modifier) {
        val compactTabs = maxWidth < 480.dp
        val jumpLabel = when {
            maxWidth >= 560.dp -> "回到此刻"
            maxWidth >= 470.dp -> "此刻"
            else -> null
        }
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = dateLabel,
                    style = HeaderDateStyle,
                    color = HomeAgendaStyle.Text,
                    maxLines = 1,
                )
                if (weekdayLabel.isNotEmpty()) {
                    Text(
                        text = weekdayLabel,
                        style = HeaderWeekdayStyle,
                        color = HomeAgendaStyle.Muted,
                        modifier = Modifier.padding(bottom = 2.dp),
                        maxLines = 1,
                    )
                }
                HomeTeachingWeekSlot(
                    teachingWeek = state.teachingWeek,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }

            if (state.agenda != null && state.agenda.entries.isNotEmpty()) {
                HomeDayAnchorBar(
                    activeDayOffset = activeDayOffset,
                    dayHasEntries = dayHasEntries,
                    showJumpToCurrent = showJumpToCurrent,
                    compact = compactTabs,
                    jumpLabel = jumpLabel,
                    onSelectDay = onSelectDay,
                    onJumpToCurrent = onJumpToCurrent,
                )
            }

            if (summary != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(summary.dotColor, CircleShape),
                    )
                    Text(
                        text = summary.text,
                        style = HeaderSummaryStyle,
                        color = HomeAgendaStyle.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 教学周预留插槽：
 * 当前未接入个人学期设置时仅保留结构与留白，不伪造硬编码周次；
 * 后续导入课程周信息后自动以轻毛玻璃徽标呈现在星期右侧。
 */
@Composable
private fun HomeTeachingWeekSlot(
    teachingWeek: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .heightIn(min = 20.dp)
            .testTag("home-teaching-week-slot"),
        contentAlignment = Alignment.CenterStart,
    ) {
        val label = teachingWeek?.trim()?.takeIf { it.isNotEmpty() }
        if (label != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(HomeAgendaStyle.CardSurface)
                    .border(1.dp, HomeAgendaStyle.CardBorderTop, RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = label,
                    style = HeaderWeekChipStyle,
                    color = HomeAgendaStyle.Muted,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 三日交互锚点与条件显现的「回到此刻」微胶囊：
 * - 滚轮滑动跨天时自动联动高亮对应日期；
 * - 点击“昨天 / 今天 / 明天”平滑弹簧滚动至该日；
 * - 当滚离当前进行中或最近事项时，在右侧柔滑浮现「回到此刻」。
 */
@Composable
private fun HomeDayAnchorBar(
    activeDayOffset: Int,
    dayHasEntries: Map<Int, Boolean>,
    showJumpToCurrent: Boolean,
    compact: Boolean,
    jumpLabel: String?,
    onSelectDay: (Int) -> Unit,
    onJumpToCurrent: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(HomeAgendaStyle.CardSurface)
                .border(1.dp, HomeAgendaStyle.CardBorderTop, RoundedCornerShape(999.dp))
                .padding(3.dp)
                .testTag("home-day-switcher"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val items = listOf(
                Triple(-1, "昨天", "home-day-yesterday"),
                Triple(0, "今天", "home-day-today"),
                Triple(1, "明天", "home-day-tomorrow"),
            )
            for ((offset, label, tag) in items) {
                val selected = activeDayOffset == offset
                val enabled = dayHasEntries[offset] == true
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (selected) Color.White else Color.Transparent)
                        .then(
                            if (selected) Modifier.border(.8.dp, HomeAgendaStyle.CardBorderBottom, RoundedCornerShape(999.dp))
                            else Modifier,
                        )
                        .clickable(
                            enabled = enabled,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelectDay(offset) },
                        )
                        .padding(horizontal = if (compact) 8.dp else 10.dp)
                        .testTag(tag),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = AnchorTabStyle,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = when {
                            selected -> HomeAgendaStyle.Text
                            enabled -> HomeAgendaStyle.Muted
                            else -> HomeAgendaStyle.Past.copy(alpha = .62f)
                        },
                        maxLines = 1,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showJumpToCurrent,
            enter = fadeIn(tween(180)) + expandHorizontally(tween(200), expandFrom = Alignment.Start),
            exit = fadeOut(tween(150)) + shrinkHorizontally(tween(180), shrinkTowards = Alignment.Start),
        ) {
            Row(
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White)
                    .border(1.dp, HomeAgendaStyle.Upcoming.copy(alpha = .28f), RoundedCornerShape(999.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onJumpToCurrent,
                    )
                    .padding(horizontal = if (jumpLabel == null) 7.dp else 8.dp)
                    .semantics { contentDescription = "回到此刻" }
                    .testTag("home-jump-current"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ReturnCurrentGlyph(color = HomeAgendaStyle.Upcoming, modifier = Modifier.size(12.dp))
                if (jumpLabel != null) {
                    Text(
                        text = jumpLabel,
                        style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
                        color = HomeAgendaStyle.Upcoming,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReturnCurrentGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.5.dp.toPx()
        val inset = stroke
        drawArc(
            color = color,
            startAngle = -55f,
            sweepAngle = 285f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2f, size.height - inset * 2f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        val tip = Offset(size.width * .78f, size.height * .26f)
        val arrow = Path().apply {
            moveTo(tip.x - 3.2.dp.toPx(), tip.y - .4.dp.toPx())
            lineTo(tip.x, tip.y)
            lineTo(tip.x - .6.dp.toPx(), tip.y - 3.2.dp.toPx())
        }
        drawPath(arrow, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private data class TodaySummaryDisplay(
    val dotColor: Color,
    val text: androidx.compose.ui.text.AnnotatedString,
)

private fun rememberTodaySummary(
    snapshot: AgendaSnapshot?,
    today: String,
    now: Long,
): TodaySummaryDisplay? {
    snapshot ?: return null
    val todayEntries = snapshot.entries.filter { ShanghaiTime.localDateOf(it.time) == today }
    if (todayEntries.isEmpty()) {
        return TodaySummaryDisplay(
            dotColor = HomeAgendaStyle.Past,
            text = buildAnnotatedString { append("今日暂无安排") },
        )
    }
    val ongoingCount = todayEntries.count { it.isOngoing(now) }
    val remainingCount = todayEntries.count { !it.completed && (it.endsAt ?: it.time) > now }
    val pendingDeadlines = todayEntries.count { it.kind == AgendaKind.TASK_DEADLINE && !it.completed }

    if (remainingCount == 0 && pendingDeadlines == 0) {
        return TodaySummaryDisplay(
            dotColor = HomeAgendaStyle.Past,
            text = buildAnnotatedString { append("今日 ${todayEntries.size} 项已毕") },
        )
    }

    val dotColor = when {
        ongoingCount > 0 -> HomeAgendaStyle.Ongoing
        remainingCount > 0 -> HomeAgendaStyle.Upcoming
        else -> HomeAgendaStyle.Ongoing.copy(alpha = .78f)
    }
    val text = buildAnnotatedString {
        if (remainingCount > 0) {
            withStyle(SpanStyle(color = HomeAgendaStyle.Text)) {
                append("今日余 $remainingCount 项")
            }
        } else {
            append("今日时段已毕")
        }
        if (pendingDeadlines > 0) {
            append("  ·  ")
            withStyle(
                SpanStyle(
                    color = if (ongoingCount > 0 || remainingCount == 0) HomeAgendaStyle.Ongoing else HomeAgendaStyle.Upcoming,
                ),
            ) {
                append("$pendingDeadlines 项截止")
            }
        }
    }
    return TodaySummaryDisplay(
        dotColor = dotColor,
        text = text,
    )
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
