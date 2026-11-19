package com.kaixu.desktop.presentation.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.kaixu.desktop.presentation.viewmodel.home.HomeQuickCreateKind
import com.kaixu.desktop.presentation.viewmodel.home.HomeQuickCreateOutcome
import com.kaixu.desktop.presentation.viewmodel.home.HomeQuickCreateRequest
import com.kaixu.desktop.presentation.viewmodel.home.HomeViewModel
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceKind
import com.kaixu.domain.usecase.scheduling.FixedEventConfirmation
import com.kaixu.domain.usecase.scheduling.ScheduleConflictKind
import com.kaixu.domain.validation.scheduling.ShanghaiTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class QuickCaptureStep { COLLAPSED, TITLE, DETAILS }
private enum class QuickCapturePopover { NONE, TIME, WORKSPACE, CONFLICT }

private data class QuickCaptureDraft(
    val kind: HomeQuickCreateKind = HomeQuickCreateKind.TASK,
    val title: String = "",
    val taskDeadlineDate: String? = null,
    val taskDeadlineTimeText: String? = null,
    val eventDate: String? = null,
    val eventStartTimeText: String = "14:00",
    val eventEndTimeText: String = "15:00",
    val workspaceId: String? = null,
)

private val CapsuleInputStyle = TextStyle(
    fontSize = 13.5.sp,
    fontWeight = FontWeight.Medium,
    color = HomeAgendaStyle.Text,
)
private val CapsuleChipStyle = TextStyle(
    fontSize = 12.sp,
    fontWeight = FontWeight.Medium,
    fontFeatureSettings = "tnum",
)
private val PopoverTitleStyle = TextStyle(
    fontSize = 12.5.sp,
    fontWeight = FontWeight.SemiBold,
    color = HomeAgendaStyle.Text,
)
private val PopoverSubStyle = TextStyle(
    fontSize = 11.5.sp,
    fontWeight = FontWeight.Medium,
    color = HomeAgendaStyle.Muted,
    fontFeatureSettings = "tnum",
)

/**
 * 首页左侧底部的交互式毛玻璃录入胶囊：
 * - 静息态：居中圆形毛玻璃 `+` 按钮；
 * - 悬停态（Step 1）：向左右弹性延展，左侧切换任务/日程，中间输入名称，右侧确认进入下一步；
 *   未输入名称时移开鼠标自动收起，已输入名称时移开保留展开与草稿；
 * - 详情态（Step 2）：同胶囊平滑切换为“时间选择芯片 + 工作区选择芯片 + 提交按钮”，
 *   时间与工作区均向上浮出轻量毛玻璃微面板且均可留空（留空工作区即独立事项）。
 */
@Composable
internal fun HomeQuickCaptureBar(
    now: Long,
    today: String,
    activeWorkspaces: List<Workspace>,
    onQuickCreate: (suspend (HomeQuickCreateRequest, FixedEventConfirmation?) -> HomeQuickCreateOutcome)?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var step by remember { mutableStateOf(QuickCaptureStep.COLLAPSED) }
    var popover by remember { mutableStateOf(QuickCapturePopover.NONE) }
    var draft by remember { mutableStateOf(QuickCaptureDraft()) }
    var pointerInside by remember { mutableStateOf(false) }
    var titleFocused by remember { mutableStateOf(false) }
    var requestTitleFocus by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var savedMessage by remember { mutableStateOf<String?>(null) }
    var pendingConflict by remember { mutableStateOf<FixedEventConfirmation?>(null) }
    val titleFocusRequester = remember { FocusRequester() }

    val hasValidTitle = draft.title.isNotBlank()
    val isExpanded = step != QuickCaptureStep.COLLAPSED || savedMessage != null

    // 未输入名称且未聚焦时，鼠标移出胶囊后平滑恢复圆形 `+` 原状。
    LaunchedEffect(step, pointerInside, titleFocused, requestTitleFocus, draft.title, popover, saving, savedMessage) {
        if (step == QuickCaptureStep.TITLE &&
            !pointerInside &&
            !titleFocused &&
            !requestTitleFocus &&
            draft.title.isBlank() &&
            popover == QuickCapturePopover.NONE &&
            !saving &&
            savedMessage == null
        ) {
            delay(180)
            step = QuickCaptureStep.COLLAPSED
            errorMessage = null
        }
    }

    LaunchedEffect(savedMessage) {
        if (savedMessage != null) {
            delay(1_500)
            savedMessage = null
            if (!pointerInside && draft.title.isBlank()) {
                step = QuickCaptureStep.COLLAPSED
            }
        }
    }

    fun resetToCollapsed() {
        draft = QuickCaptureDraft()
        popover = QuickCapturePopover.NONE
        pendingConflict = null
        errorMessage = null
        titleFocused = false
        step = QuickCaptureStep.COLLAPSED
    }

    fun advanceToDetails() {
        if (!hasValidTitle || saving) return
        errorMessage = null
        popover = QuickCapturePopover.NONE
        step = QuickCaptureStep.DETAILS
    }

    fun submit(confirmed: FixedEventConfirmation? = null) {
        if (saving) return
        val handler = onQuickCreate
        if (handler == null) {
            errorMessage = "当前预览模式未连接本地库。"
            return
        }
        val request = buildQuickCreateRequest(draft)
            .getOrElse { failure ->
                errorMessage = failure.message ?: "时间格式无效，请检查后重试。"
                return
            }
        saving = true
        errorMessage = null
        popover = QuickCapturePopover.NONE
        scope.launch {
            val outcome = handler(request, confirmed)
            saving = false
            when (outcome) {
                is HomeQuickCreateOutcome.Saved -> {
                    draft = QuickCaptureDraft(kind = draft.kind)
                    pendingConflict = null
                    errorMessage = null
                    titleFocused = false
                    step = QuickCaptureStep.COLLAPSED
                    savedMessage = outcome.message
                }
                is HomeQuickCreateOutcome.NeedsConflictConfirmation -> {
                    pendingConflict = outcome.confirmation
                    popover = QuickCapturePopover.CONFLICT
                }
                is HomeQuickCreateOutcome.Failed -> {
                    pendingConflict = null
                    errorMessage = outcome.message
                }
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier.testTag("home-quick-capture"),
        contentAlignment = Alignment.Center,
    ) {
        val collapsedWidth = 44.dp
        val capsuleHeight = 44.dp
        val expandedTargetWidth = when {
            savedMessage != null -> 196.dp
            else -> maxWidth.coerceIn(collapsedWidth, 536.dp)
        }
        val capsuleWidth by animateDpAsState(
            targetValue = if (isExpanded) expandedTargetWidth else collapsedWidth,
            animationSpec = spring(dampingRatio = .82f, stiffness = 360f),
            label = "capture-capsule-width",
        )
        val expandFraction by animateFloatAsState(
            targetValue = if (isExpanded) 1f else 0f,
            animationSpec = spring(dampingRatio = .86f, stiffness = 380f),
            label = "capture-capsule-alpha",
        )
        val capsuleShape = RoundedCornerShape(capsuleHeight / 2)
        val outerBorder = Brush.verticalGradient(
            listOf(
                if (popover != QuickCapturePopover.NONE || titleFocused) {
                    HomeAgendaStyle.Upcoming.copy(alpha = .32f)
                } else {
                    HomeAgendaStyle.CardBorderTop
                },
                HomeAgendaStyle.CardBorderBottom,
            ),
        )

        Box(contentAlignment = Alignment.Center) {
            // 向上浮出的毛玻璃微面板（时间选择 / 工作区选择 / 冲突确认 / 校验提示）
            val popupOffsetY = -with(density) { (capsuleHeight + 10.dp).roundToPx() }
            if (popover != QuickCapturePopover.NONE) {
                Popup(
                    alignment = Alignment.BottomCenter,
                    offset = IntOffset(0, popupOffsetY),
                    onDismissRequest = {
                        popover = QuickCapturePopover.NONE
                        if (pendingConflict != null) pendingConflict = null
                    },
                    properties = PopupProperties(focusable = true),
                ) {
                    when (popover) {
                        QuickCapturePopover.TIME -> TimePickerPopover(
                            draft = draft,
                            now = now,
                            today = today,
                            onDraftChange = {
                                draft = it
                                errorMessage = null
                            },
                            onDone = { popover = QuickCapturePopover.NONE },
                        )
                        QuickCapturePopover.WORKSPACE -> WorkspacePickerPopover(
                            workspaces = activeWorkspaces,
                            selectedId = draft.workspaceId,
                            onSelect = { id ->
                                draft = draft.copy(workspaceId = id)
                                errorMessage = null
                                popover = QuickCapturePopover.NONE
                            },
                        )
                        QuickCapturePopover.CONFLICT -> pendingConflict?.let { conflict ->
                            ConflictConfirmPopover(
                                confirmation = conflict,
                                saving = saving,
                                onAdjustTime = {
                                    pendingConflict = null
                                    popover = QuickCapturePopover.TIME
                                },
                                onConfirmSave = { submit(conflict) },
                            )
                        }
                        QuickCapturePopover.NONE -> Unit
                    }
                }
            } else if (errorMessage != null) {
                Popup(
                    alignment = Alignment.BottomCenter,
                    offset = IntOffset(0, popupOffsetY),
                    onDismissRequest = { errorMessage = null },
                    properties = PopupProperties(focusable = false),
                ) {
                    FrostedPopoverShell(modifier = Modifier.widthIn(max = 360.dp).testTag("home-capture-error")) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.size(6.dp).background(HomeAgendaStyle.Ongoing, CircleShape))
                            Text(
                                text = errorMessage.orEmpty(),
                                style = PopoverSubStyle,
                                color = HomeAgendaStyle.Ongoing,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .width(capsuleWidth)
                    .height(capsuleHeight)
                    .shadow(
                        elevation = if (isExpanded) 8.dp else 5.dp,
                        shape = capsuleShape,
                        clip = false,
                        ambientColor = HomeAgendaStyle.CardShadow.copy(alpha = .06f),
                        spotColor = HomeAgendaStyle.CardShadow.copy(alpha = .12f),
                    )
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                when (event.type) {
                                    PointerEventType.Enter -> {
                                        pointerInside = true
                                        if (step == QuickCaptureStep.COLLAPSED && savedMessage == null) {
                                            step = QuickCaptureStep.TITLE
                                        }
                                    }
                                    PointerEventType.Exit -> pointerInside = false
                                }
                            }
                        }
                    }
                    .testTag("home-capture-capsule"),
                shape = capsuleShape,
                color = HomeAgendaStyle.CardSurface,
                border = BorderStroke(1.dp, outerBorder),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind { drawFrostedPillSurface(radiusPx = size.height / 2f) },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        savedMessage != null -> {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .testTag("home-capture-saved"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CheckGlyph(color = HomeAgendaStyle.Upcoming, modifier = Modifier.size(14.dp))
                                Text(
                                    text = savedMessage.orEmpty(),
                                    style = CapsuleChipStyle,
                                    color = HomeAgendaStyle.Upcoming,
                                    maxLines = 1,
                                )
                            }
                        }
                        step == QuickCaptureStep.COLLAPSED -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(capsuleShape)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        role = Role.Button,
                                        onClick = {
                                            step = QuickCaptureStep.TITLE
                                            requestTitleFocus = true
                                        },
                                    )
                                    .semantics { contentDescription = "快速新建任务或日程" }
                                    .testTag("home-capture-toggle"),
                                contentAlignment = Alignment.Center,
                            ) {
                                PlusGlyph(color = HomeAgendaStyle.Text, modifier = Modifier.size(16.dp))
                            }
                        }
                        else -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { alpha = expandFraction.coerceIn(0f, 1f) },
                                contentAlignment = Alignment.Center,
                            ) {
                                AnimatedContent(
                                    targetState = step,
                                    transitionSpec = {
                                        val forward = targetState == QuickCaptureStep.DETAILS
                                        (fadeIn(tween(170)) + slideInHorizontally(tween(200)) { if (forward) it / 8 else -it / 8 })
                                            .togetherWith(
                                                fadeOut(tween(130)) + slideOutHorizontally(tween(160)) { if (forward) -it / 8 else it / 8 },
                                            )
                                    },
                                    label = "capture-step-content",
                                ) { activeStep ->
                                    when (activeStep) {
                                        QuickCaptureStep.TITLE -> CaptureTitleStepRow(
                                            draft = draft,
                                            focusRequester = titleFocusRequester,
                                            requestFocus = requestTitleFocus,
                                            onFocusRequested = { requestTitleFocus = false },
                                            onFocusChanged = { titleFocused = it },
                                            onKindChange = { kind ->
                                                draft = draft.copy(kind = kind)
                                                errorMessage = null
                                            },
                                            onTitleChange = { text ->
                                                draft = draft.copy(title = text)
                                                errorMessage = null
                                            },
                                            onClearOrCollapse = { resetToCollapsed() },
                                            onConfirmTitle = { advanceToDetails() },
                                        )
                                        QuickCaptureStep.DETAILS -> CaptureDetailsStepRow(
                                            draft = draft,
                                            today = today,
                                            activeWorkspaces = activeWorkspaces,
                                            activePopover = popover,
                                            saving = saving,
                                            onBackToTitle = {
                                                popover = QuickCapturePopover.NONE
                                                errorMessage = null
                                                step = QuickCaptureStep.TITLE
                                            },
                                            onToggleTimePopover = {
                                                errorMessage = null
                                                popover = if (popover == QuickCapturePopover.TIME) QuickCapturePopover.NONE else QuickCapturePopover.TIME
                                            },
                                            onClearTime = {
                                                errorMessage = null
                                                draft = when (draft.kind) {
                                                    HomeQuickCreateKind.TASK -> draft.copy(taskDeadlineDate = null, taskDeadlineTimeText = null)
                                                    HomeQuickCreateKind.EVENT -> draft.copy(eventDate = null)
                                                }
                                            },
                                            onToggleWorkspacePopover = {
                                                errorMessage = null
                                                popover = if (popover == QuickCapturePopover.WORKSPACE) QuickCapturePopover.NONE else QuickCapturePopover.WORKSPACE
                                            },
                                            onClearWorkspace = {
                                                errorMessage = null
                                                draft = draft.copy(workspaceId = null)
                                            },
                                            onSubmit = { submit() },
                                        )
                                        QuickCaptureStep.COLLAPSED -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureTitleStepRow(
    draft: QuickCaptureDraft,
    focusRequester: FocusRequester,
    requestFocus: Boolean,
    onFocusRequested: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onKindChange: (HomeQuickCreateKind) -> Unit,
    onTitleChange: (String) -> Unit,
    onClearOrCollapse: () -> Unit,
    onConfirmTitle: () -> Unit,
) {
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            onFocusRequested()
            runCatching { focusRequester.requestFocus() }
        }
    }
    val canAdvance = draft.title.isNotBlank()
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp)
            .testTag("home-capture-step-title"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 左侧：类别切换胶囊（任务 / 日程）
        KindSwitchPill(
            selected = draft.kind,
            onSelect = onKindChange,
        )

        Box(Modifier.width(1.dp).height(18.dp).background(HomeAgendaStyle.CardBorderTop))

        // 中间：名称输入框
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (draft.title.isEmpty()) {
                Text(
                    text = if (draft.kind == HomeQuickCreateKind.TASK) "输入任务名称…" else "输入日程名称…",
                    style = CapsuleInputStyle,
                    color = HomeAgendaStyle.Past,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = draft.title,
                onValueChange = { onTitleChange(it.replace('\n', ' ')) },
                singleLine = true,
                textStyle = CapsuleInputStyle,
                cursorBrush = SolidColor(HomeAgendaStyle.Upcoming),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { onFocusChanged(it.isFocused) }
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown) {
                            when (keyEvent.key) {
                                Key.Enter, Key.NumPadEnter -> {
                                    if (canAdvance) onConfirmTitle()
                                    true
                                }
                                Key.Escape -> {
                                    onClearOrCollapse()
                                    true
                                }
                                else -> false
                            }
                        } else false
                    }
                    .testTag("home-capture-title"),
            )
        }

        // 若已有草稿，提供极轻的清空收起按钮
        if (canAdvance) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onClearOrCollapse,
                    )
                    .semantics { contentDescription = "清空并收起" }
                    .testTag("home-capture-cancel"),
                contentAlignment = Alignment.Center,
            ) {
                CloseGlyph(color = HomeAgendaStyle.Past, modifier = Modifier.size(10.dp))
            }
        }

        // 右侧：下一步确认按钮
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (canAdvance) HomeAgendaStyle.Upcoming else Color(0xFFECEEF2))
                .clickable(
                    enabled = canAdvance,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onConfirmTitle,
                )
                .semantics { contentDescription = "下一步设置时间与工作区" }
                .testTag("home-capture-next"),
            contentAlignment = Alignment.Center,
        ) {
            ArrowGlyph(
                forward = true,
                color = if (canAdvance) Color.White else HomeAgendaStyle.Past,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun CaptureDetailsStepRow(
    draft: QuickCaptureDraft,
    today: String,
    activeWorkspaces: List<Workspace>,
    activePopover: QuickCapturePopover,
    saving: Boolean,
    onBackToTitle: () -> Unit,
    onToggleTimePopover: () -> Unit,
    onClearTime: () -> Unit,
    onToggleWorkspacePopover: () -> Unit,
    onClearWorkspace: () -> Unit,
    onSubmit: () -> Unit,
) {
    val timeLabel = formatTimeChipLabel(draft, today)
    val hasExplicitTime = when (draft.kind) {
        HomeQuickCreateKind.TASK -> draft.taskDeadlineDate != null
        HomeQuickCreateKind.EVENT -> draft.eventDate != null
    }
    val workspaceName = activeWorkspaces.firstOrNull { it.id == draft.workspaceId }?.name

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp)
            .testTag("home-capture-step-details"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 返回修改标题/类别
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = .75f))
                .border(1.dp, HomeAgendaStyle.CardBorderTop, CircleShape)
                .clickable(
                    enabled = !saving,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onBackToTitle,
                )
                .semantics { contentDescription = "返回修改名称与类别" }
                .testTag("home-capture-back"),
            contentAlignment = Alignment.Center,
        ) {
            ArrowGlyph(forward = false, color = HomeAgendaStyle.Muted, modifier = Modifier.size(13.dp))
        }

        // 微缩标题回顾
        Row(
            modifier = Modifier.widthIn(max = 108.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            KindMiniGlyph(
                kind = draft.kind,
                color = HomeAgendaStyle.Upcoming,
                modifier = Modifier.size(8.dp),
            )
            Text(
                text = draft.title.trim(),
                style = CapsuleChipStyle,
                color = HomeAgendaStyle.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(Modifier.width(1.dp).height(18.dp).background(HomeAgendaStyle.CardBorderTop))

        // 时间选择芯片按钮
        DetailTriggerChip(
            label = timeLabel,
            active = activePopover == QuickCapturePopover.TIME,
            filled = hasExplicitTime,
            tag = "home-capture-time-btn",
            clearTag = "home-capture-time-clear",
            onClick = onToggleTimePopover,
            onClear = if (hasExplicitTime) onClearTime else null,
            modifier = Modifier.weight(1.15f),
            icon = { tint -> ClockGlyph(color = tint, modifier = Modifier.size(13.dp)) },
        )

        // 工作区选择芯片按钮
        DetailTriggerChip(
            label = workspaceName ?: "独立事项",
            active = activePopover == QuickCapturePopover.WORKSPACE,
            filled = workspaceName != null,
            tag = "home-capture-workspace-btn",
            clearTag = "home-capture-workspace-clear",
            onClick = onToggleWorkspacePopover,
            onClear = if (workspaceName != null) onClearWorkspace else null,
            modifier = Modifier.weight(1f),
            icon = { tint -> FolderMiniGlyph(color = tint, modifier = Modifier.size(13.dp)) },
        )

        // 最终提交按钮
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(HomeAgendaStyle.Upcoming)
                .clickable(
                    enabled = !saving,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onSubmit,
                )
                .semantics { contentDescription = "保存新建事项" }
                .testTag("home-capture-submit"),
            contentAlignment = Alignment.Center,
        ) {
            if (saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.6.dp,
                    color = Color.White,
                )
            } else {
                CheckGlyph(color = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun KindSwitchPill(
    selected: HomeQuickCreateKind,
    onSelect: (HomeQuickCreateKind) -> Unit,
) {
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFF0F2F5))
            .border(1.dp, HomeAgendaStyle.CardBorderTop, RoundedCornerShape(16.dp))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((kind, label, tag) in listOf(
            Triple(HomeQuickCreateKind.TASK, "任务", "home-capture-kind-task"),
            Triple(HomeQuickCreateKind.EVENT, "日程", "home-capture-kind-event"),
        )) {
            val isSelected = selected == kind
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isSelected) Color.White else Color.Transparent)
                    .then(
                        if (isSelected) Modifier.border(.8.dp, HomeAgendaStyle.CardBorderBottom, RoundedCornerShape(14.dp))
                        else Modifier,
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                        onClick = { onSelect(kind) },
                    )
                    .padding(horizontal = 9.dp)
                    .testTag(tag),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                KindMiniGlyph(
                    kind = kind,
                    color = if (isSelected) HomeAgendaStyle.Upcoming else HomeAgendaStyle.Past,
                    modifier = Modifier.size(7.dp),
                )
                Text(
                    text = label,
                    style = CapsuleChipStyle,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) HomeAgendaStyle.Text else HomeAgendaStyle.Muted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun DetailTriggerChip(
    label: String,
    active: Boolean,
    filled: Boolean,
    tag: String,
    clearTag: String,
    onClick: () -> Unit,
    onClear: (() -> Unit)?,
    modifier: Modifier = Modifier,
    icon: @Composable (Color) -> Unit,
) {
    val bg = when {
        active -> Color.White
        filled -> Color.White.copy(alpha = .92f)
        else -> Color(0xFFF1F3F6).copy(alpha = .85f)
    }
    val borderColor = when {
        active -> HomeAgendaStyle.Upcoming.copy(alpha = .48f)
        filled -> HomeAgendaStyle.Upcoming.copy(alpha = .24f)
        else -> HomeAgendaStyle.CardBorderTop
    }
    val contentColor = if (filled || active) HomeAgendaStyle.Upcoming else HomeAgendaStyle.Muted
    Row(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(15.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 9.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        icon(contentColor)
        Text(
            text = label,
            style = CapsuleChipStyle,
            color = if (filled) HomeAgendaStyle.Text else contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onClear != null) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onClear,
                    )
                    .semantics { contentDescription = "清除已选项" }
                    .testTag(clearTag),
                contentAlignment = Alignment.Center,
            ) {
                CloseGlyph(color = HomeAgendaStyle.Past, modifier = Modifier.size(8.dp))
            }
        }
    }
}

@Composable
private fun TimePickerPopover(
    draft: QuickCaptureDraft,
    now: Long,
    today: String,
    onDraftChange: (QuickCaptureDraft) -> Unit,
    onDone: () -> Unit,
) {
    val initialDate = when (draft.kind) {
        HomeQuickCreateKind.TASK -> draft.taskDeadlineDate ?: today
        HomeQuickCreateKind.EVENT -> draft.eventDate ?: today
    }
    val (defaultStartMs, defaultEndMs) = remember(now) { HomeViewModel.defaultEventRange(now) }
    val initialEventStart = if (draft.eventDate != null) draft.eventStartTimeText else ShanghaiTime.format(defaultStartMs).substring(11)
    val initialEventEnd = if (draft.eventDate != null) draft.eventEndTimeText else ShanghaiTime.format(defaultEndMs).substring(11)
    val initialTaskTime = draft.taskDeadlineTimeText ?: "21:00"

    var yearText by remember(draft.kind) { mutableStateOf(initialDate.substring(0, 4)) }
    var monthText by remember(draft.kind) { mutableStateOf(initialDate.substring(5, 7)) }
    var dayText by remember(draft.kind) { mutableStateOf(initialDate.substring(8, 10)) }
    var calendarOpen by remember(draft.kind) { mutableStateOf(false) }
    var calYear by remember(draft.kind) { mutableIntStateOf(initialDate.substring(0, 4).toIntOrNull() ?: 2026) }
    var calMonth by remember(draft.kind) { mutableIntStateOf(initialDate.substring(5, 7).toIntOrNull() ?: 1) }

    var taskExactMode by remember(draft.kind) { mutableStateOf(draft.taskDeadlineTimeText != null) }
    var taskHour by remember(draft.kind) { mutableStateOf(splitHourMinute(initialTaskTime).first) }
    var taskMinute by remember(draft.kind) { mutableStateOf(splitHourMinute(initialTaskTime).second) }

    var startHour by remember(draft.kind) { mutableStateOf(splitHourMinute(initialEventStart).first) }
    var startMinute by remember(draft.kind) { mutableStateOf(splitHourMinute(initialEventStart).second) }
    var endHour by remember(draft.kind) { mutableStateOf(splitHourMinute(initialEventEnd).first) }
    var endMinute by remember(draft.kind) { mutableStateOf(splitHourMinute(initialEventEnd).second) }

    var localError by remember(draft.kind) { mutableStateOf<String?>(null) }

    fun parsedDateOrNull(): String? {
        val y = yearText.toIntOrNull() ?: return null
        val m = monthText.toIntOrNull() ?: return null
        val d = dayText.toIntOrNull() ?: return null
        if (y !in 1..9999 || m !in 1..12) return null
        val candidate = "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
        return candidate.takeIf(ShanghaiTime::isValidDate)
    }

    fun formatValidHourMinute(hourStr: String, minuteStr: String): String? {
        val h = hourStr.toIntOrNull() ?: return null
        val m = minuteStr.toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
    }

    fun syncCalendarMonthFromInputs() {
        val y = yearText.toIntOrNull()
        val m = monthText.toIntOrNull()
        if (y != null && y in 1..9999 && m != null && m in 1..12) {
            calYear = y
            calMonth = m
        }
    }

    fun applyAndClose() {
        val date = parsedDateOrNull()
        if (date == null) {
            localError = "请输入有效的年、月、日"
            return
        }
        if (draft.kind == HomeQuickCreateKind.TASK) {
            if (!taskExactMode) {
                onDraftChange(draft.copy(taskDeadlineDate = date, taskDeadlineTimeText = null))
                onDone()
            } else {
                val hhmm = formatValidHourMinute(taskHour, taskMinute)
                if (hhmm == null) {
                    localError = "请输入有效小时（00–23）与分钟（00–59）"
                    return
                }
                onDraftChange(draft.copy(taskDeadlineDate = date, taskDeadlineTimeText = hhmm))
                onDone()
            }
        } else {
            val startText = formatValidHourMinute(startHour, startMinute)
            val endText = formatValidHourMinute(endHour, endMinute)
            if (startText == null || endText == null) {
                localError = "请输入有效的起止小时与分钟"
                return
            }
            val startMs = ShanghaiTime.parseOrNull("$date $startText")
            val endMs = ShanghaiTime.parseOrNull("$date $endText")
            if (startMs == null || endMs == null || endMs <= startMs) {
                localError = "结束时间必须晚于开始时间"
                return
            }
            onDraftChange(draft.copy(eventDate = date, eventStartTimeText = startText, eventEndTimeText = endText))
            onDone()
        }
    }

    FrostedPopoverShell(modifier = Modifier.width(322.dp).testTag("home-capture-time-popover")) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            // 标题行：日程仅留标题，任务保留简洁的“不设截止”清空入口
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (draft.kind == HomeQuickCreateKind.TASK) "截止时间" else "日程起止时间",
                    style = PopoverTitleStyle,
                )
                if (draft.kind == HomeQuickCreateKind.TASK) {
                    Text(
                        text = "不设截止",
                        style = PopoverSubStyle,
                        color = HomeAgendaStyle.Upcoming,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClick = {
                                    onDraftChange(draft.copy(taskDeadlineDate = null, taskDeadlineTimeText = null))
                                    onDone()
                                },
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("home-capture-time-reset"),
                    )
                }
            }

            // 年 / 月 / 日 三栏手动输入 + 日历切换按钮
            DateTripletInputBar(
                year = yearText,
                month = monthText,
                day = dayText,
                calendarOpen = calendarOpen,
                onYearChange = {
                    yearText = it
                    localError = null
                    syncCalendarMonthFromInputs()
                },
                onMonthChange = {
                    monthText = it
                    localError = null
                    syncCalendarMonthFromInputs()
                },
                onDayChange = {
                    dayText = it
                    localError = null
                },
                onToggleCalendar = {
                    syncCalendarMonthFromInputs()
                    calendarOpen = !calendarOpen
                },
            )

            // 可展开的迷你月历视图
            AnimatedVisibility(
                visible = calendarOpen,
                enter = fadeIn(tween(150)) + expandVertically(tween(180)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(150)),
            ) {
                MiniMonthCalendar(
                    year = calYear,
                    month = calMonth,
                    selectedDate = parsedDateOrNull(),
                    today = today,
                    onPrevMonth = {
                        if (calMonth == 1) {
                            if (calYear > 1) {
                                calYear -= 1
                                calMonth = 12
                            }
                        } else {
                            calMonth -= 1
                        }
                    },
                    onNextMonth = {
                        if (calMonth == 12) {
                            if (calYear < 9999) {
                                calYear += 1
                                calMonth = 1
                            }
                        } else {
                            calMonth += 1
                        }
                    },
                    onSelectDate = { picked ->
                        yearText = picked.substring(0, 4)
                        monthText = picked.substring(5, 7)
                        dayText = picked.substring(8, 10)
                        localError = null
                        calendarOpen = false
                    },
                )
            }

            if (draft.kind == HomeQuickCreateKind.TASK) {
                // 任务：保留“仅日期 · 当天截止”与“具体时刻”切换
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MiniPresetPill(
                        label = "仅日期 · 当天截止",
                        selected = !taskExactMode,
                        onClick = {
                            taskExactMode = false
                            localError = null
                        },
                        modifier = Modifier.weight(1f).testTag("home-capture-task-dateonly"),
                    )
                    MiniPresetPill(
                        label = "具体时刻",
                        selected = taskExactMode,
                        onClick = {
                            taskExactMode = true
                            localError = null
                        },
                        modifier = Modifier.weight(1f).testTag("home-capture-task-exacttime"),
                    )
                }

                if (taskExactMode) {
                    SplitHourMinuteField(
                        label = "截止时刻",
                        hour = taskHour,
                        minute = taskMinute,
                        hourTag = "home-capture-task-hour",
                        minuteTag = "home-capture-task-minute",
                        onHourChange = { taskHour = it; localError = null },
                        onMinuteChange = { taskMinute = it; localError = null },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                // 日程：开始 / 结束均拆分为“小时 : 分钟”双框输入
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SplitHourMinuteField(
                        label = "开始",
                        hour = startHour,
                        minute = startMinute,
                        hourTag = "home-capture-event-start-hour",
                        minuteTag = "home-capture-event-start-minute",
                        onHourChange = { startHour = it; localError = null },
                        onMinuteChange = { startMinute = it; localError = null },
                        modifier = Modifier.weight(1f),
                    )
                    Text("—", style = PopoverSubStyle)
                    SplitHourMinuteField(
                        label = "结束",
                        hour = endHour,
                        minute = endMinute,
                        hourTag = "home-capture-event-end-hour",
                        minuteTag = "home-capture-event-end-minute",
                        onHourChange = { endHour = it; localError = null },
                        onMinuteChange = { endMinute = it; localError = null },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 底部仅保留错误提示（如有）与右侧确定按钮，不重复显示已选日期结果
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                localError?.let { msg ->
                    Text(
                        text = msg,
                        style = PopoverSubStyle,
                        color = HomeAgendaStyle.Ongoing,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp).testTag("home-capture-time-error"),
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(HomeAgendaStyle.Upcoming)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = ::applyAndClose,
                        )
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .testTag("home-capture-time-done"),
                ) {
                    Text("确定", style = CapsuleChipStyle, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun DateTripletInputBar(
    year: String,
    month: String,
    day: String,
    calendarOpen: Boolean,
    onYearChange: (String) -> Unit,
    onMonthChange: (String) -> Unit,
    onDayChange: (String) -> Unit,
    onToggleCalendar: () -> Unit,
) {
    val monthFocus = remember { FocusRequester() }
    val dayFocus = remember { FocusRequester() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Color.White)
            .border(1.dp, HomeAgendaStyle.CardBorderTop, RoundedCornerShape(11.dp))
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NumericSegmentInput(
            value = year,
            maxLength = 4,
            placeholder = "YYYY",
            tag = "home-capture-date-year",
            onValueChange = { next ->
                onYearChange(next)
                if (next.length == 4 && year.length < 4) runCatching { monthFocus.requestFocus() }
            },
            modifier = Modifier
                .width(46.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFF5F6F8))
                .padding(vertical = 3.dp),
        )
        Text("年", style = PopoverSubStyle)
        NumericSegmentInput(
            value = month,
            maxLength = 2,
            placeholder = "MM",
            tag = "home-capture-date-month",
            onValueChange = { next ->
                onMonthChange(next)
                if (next.length == 2 && month.length < 2) runCatching { dayFocus.requestFocus() }
            },
            modifier = Modifier
                .width(30.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFF5F6F8))
                .padding(vertical = 3.dp)
                .focusRequester(monthFocus),
        )
        Text("月", style = PopoverSubStyle)
        NumericSegmentInput(
            value = day,
            maxLength = 2,
            placeholder = "DD",
            tag = "home-capture-date-day",
            onValueChange = onDayChange,
            modifier = Modifier
                .width(30.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFF5F6F8))
                .padding(vertical = 3.dp)
                .focusRequester(dayFocus),
        )
        Text("日", style = PopoverSubStyle)

        Spacer(Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(16.dp).background(HomeAgendaStyle.CardBorderTop))
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (calendarOpen) Color(0xFFEEF3F4) else Color.Transparent)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onToggleCalendar,
                )
                .semantics { contentDescription = "展开或收起日历" }
                .testTag("home-capture-calendar-toggle"),
            contentAlignment = Alignment.Center,
        ) {
            CalendarMiniGlyph(
                color = if (calendarOpen) HomeAgendaStyle.Upcoming else HomeAgendaStyle.Muted,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun SplitHourMinuteField(
    label: String,
    hour: String,
    minute: String,
    hourTag: String,
    minuteTag: String,
    onHourChange: (String) -> Unit,
    onMinuteChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val minuteFocus = remember { FocusRequester() }
    Row(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Color.White)
            .border(1.dp, HomeAgendaStyle.CardBorderTop, RoundedCornerShape(11.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = PopoverSubStyle)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            NumericSegmentInput(
                value = hour,
                maxLength = 2,
                placeholder = "00",
                tag = hourTag,
                onValueChange = { next ->
                    onHourChange(next)
                    if (next.length == 2 && hour.length < 2) runCatching { minuteFocus.requestFocus() }
                },
                modifier = Modifier
                    .width(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFF5F6F8))
                    .padding(vertical = 3.dp),
            )
            Text(
                text = ":",
                style = CapsuleChipStyle.copy(fontWeight = FontWeight.SemiBold),
                color = HomeAgendaStyle.Muted,
            )
            NumericSegmentInput(
                value = minute,
                maxLength = 2,
                placeholder = "00",
                tag = minuteTag,
                onValueChange = onMinuteChange,
                modifier = Modifier
                    .width(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFF5F6F8))
                    .padding(vertical = 3.dp)
                    .focusRequester(minuteFocus),
            )
        }
    }
}

@Composable
private fun NumericSegmentInput(
    value: String,
    maxLength: Int,
    placeholder: String,
    tag: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = CapsuleChipStyle.copy(textAlign = TextAlign.Center),
                color = HomeAgendaStyle.Past,
                maxLines = 1,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = { next ->
                val digits = next.filter(Char::isDigit).take(maxLength)
                onValueChange(digits)
            },
            singleLine = true,
            textStyle = CapsuleChipStyle.copy(
                color = HomeAgendaStyle.Text,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            ),
            cursorBrush = SolidColor(HomeAgendaStyle.Upcoming),
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
    }
}

@Composable
private fun MiniMonthCalendar(
    year: Int,
    month: Int,
    selectedDate: String?,
    today: String,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelectDate: (String) -> Unit,
) {
    val cells = remember(year, month) { buildMonthGrid(year, month) }
    val weekdayHeaders = listOf("一", "二", "三", "四", "五", "六", "日")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = .92f))
            .border(1.dp, HomeAgendaStyle.CardBorderTop, RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .testTag("home-capture-calendar-grid"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StepArrowButton(forward = false, tag = "home-capture-cal-prev-month", onClick = onPrevMonth)
            Text(
                text = "${year}年 ${month}月",
                style = CapsuleChipStyle.copy(fontWeight = FontWeight.SemiBold),
                color = HomeAgendaStyle.Text,
            )
            StepArrowButton(forward = true, tag = "home-capture-cal-next-month", onClick = onNextMonth)
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            for (header in weekdayHeaders) {
                Box(Modifier.weight(1f).height(20.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = header,
                        style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
                        color = HomeAgendaStyle.Past,
                    )
                }
            }
        }

        for (week in cells.chunked(7)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (day in week) {
                    if (day == null) {
                        Spacer(Modifier.weight(1f).height(26.dp))
                    } else {
                        val iso = "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
                        val isSelected = iso == selectedDate
                        val isToday = iso == today
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(26.dp)
                                .padding(horizontal = 2.dp, vertical = 1.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) HomeAgendaStyle.Upcoming else Color.Transparent)
                                .then(
                                    if (!isSelected && isToday) Modifier.border(1.dp, HomeAgendaStyle.Upcoming.copy(alpha = .42f), RoundedCornerShape(8.dp))
                                    else Modifier,
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                    onClick = { onSelectDate(iso) },
                                )
                                .testTag("home-capture-cal-day-$iso"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = day.toString(),
                                style = TextStyle(
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected || isToday) FontWeight.SemiBold else FontWeight.Medium,
                                    fontFeatureSettings = "tnum",
                                ),
                                color = when {
                                    isSelected -> Color.White
                                    isToday -> HomeAgendaStyle.Upcoming
                                    else -> HomeAgendaStyle.Text
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private val WorkspaceOptionHeight = 36.dp
private val WorkspaceOptionGap = 4.dp
private val WorkspaceListMaxHeight = WorkspaceOptionHeight * 4 + WorkspaceOptionGap * 3

@Composable
private fun WorkspacePickerPopover(
    workspaces: List<Workspace>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    FrostedPopoverShell(modifier = Modifier.width(276.dp).testTag("home-capture-workspace-popover")) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "关联工作区",
                style = PopoverTitleStyle,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )

            WorkspaceOptionRow(
                title = "独立事项",
                badge = null,
                selected = selectedId == null,
                tag = "home-capture-ws-none",
                onClick = { onSelect(null) },
            )

            Box(Modifier.fillMaxWidth().height(1.dp).background(HomeAgendaStyle.CardBorderTop))

            if (workspaces.isEmpty()) {
                Text(
                    text = "暂无进行中的工作区，当前将保存为独立事项",
                    style = PopoverSubStyle,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                )
            } else {
                val scrollState = rememberScrollState()
                val scrollScope = rememberCoroutineScope()
                val density = LocalDensity.current
                val rowStepPx = with(density) { (WorkspaceOptionHeight + WorkspaceOptionGap).toPx() }
                val scrollable = workspaces.size > 4
                var wheelTargetPx by remember(scrollState) { mutableFloatStateOf(0f) }
                var wheelJob by remember(scrollState) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = WorkspaceListMaxHeight)
                        .pointerInput(scrollState, rowStepPx, scrollable) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.type != PointerEventType.Scroll) continue
                                    val deltaY = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                                    if (deltaY == 0f || !scrollable || scrollState.maxValue <= 0) continue
                                    event.changes.forEach { it.consume() }
                                    val base = if (wheelJob?.isActive == true) wheelTargetPx else scrollState.value.toFloat()
                                    val next = (base + deltaY * rowStepPx).coerceIn(0f, scrollState.maxValue.toFloat())
                                    wheelTargetPx = next
                                    wheelJob?.cancel()
                                    wheelJob = scrollScope.launch {
                                        scrollState.animateScrollTo(next.toInt(), tween(110))
                                    }
                                }
                            }
                        }
                        .testTag("home-capture-workspace-list"),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(end = if (scrollable) 8.dp else 0.dp),
                        verticalArrangement = Arrangement.spacedBy(WorkspaceOptionGap),
                    ) {
                        for (space in workspaces) {
                            WorkspaceOptionRow(
                                title = space.name,
                                badge = if (space.kind == WorkspaceKind.COURSE) "课程" else "项目",
                                selected = selectedId == space.id,
                                tag = "home-capture-ws-${space.id}",
                                onClick = { onSelect(space.id) },
                            )
                        }
                    }
                    if (scrollable) {
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(scrollState),
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .padding(vertical = 2.dp)
                                .testTag("home-capture-workspace-scrollbar"),
                            style = ScrollbarStyle(
                                minimalHeight = 24.dp,
                                thickness = 4.dp,
                                shape = RoundedCornerShape(999.dp),
                                hoverDurationMillis = 150,
                                unhoverColor = HomeAgendaStyle.Past.copy(alpha = .45f),
                                hoverColor = HomeAgendaStyle.Muted.copy(alpha = .78f),
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceOptionRow(
    title: String,
    badge: String?,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()

    val hoverBg = Color(0xFFEEF1F5)
    val backgroundColor by animateColorAsState(
        targetValue = when {
            selected && pressed -> Color(0xFFF3F6F7)
            selected -> Color.White
            pressed -> Color(0xFFE7EBF1)
            hovered -> hoverBg
            // 使用与悬停底色同色相的 0 透明色，避免 Color.Transparent（透明黑）在 Oklab 插值中途发黑闪烁。
            else -> hoverBg.copy(alpha = 0f)
        },
        animationSpec = tween(100),
        label = "ws-option-bg",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            selected && hovered -> HomeAgendaStyle.Upcoming.copy(alpha = .46f)
            selected -> HomeAgendaStyle.Upcoming.copy(alpha = .28f)
            hovered -> HomeAgendaStyle.CardBorderBottom
            else -> HomeAgendaStyle.CardBorderBottom.copy(alpha = 0f)
        },
        animationSpec = tween(100),
        label = "ws-option-border",
    )
    val textColor by animateColorAsState(
        targetValue = if (selected || hovered) HomeAgendaStyle.Text else HomeAgendaStyle.Muted,
        animationSpec = tween(100),
        label = "ws-option-text",
    )
    val badgeBg by animateColorAsState(
        targetValue = if (hovered && !selected) Color.White.copy(alpha = .92f) else Color(0xFFF0F2F5),
        animationSpec = tween(100),
        label = "ws-option-badge-bg",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(WorkspaceOptionHeight)
            .clip(RoundedCornerShape(10.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .hoverable(interactions)
            .clickable(
                interactionSource = interactions,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (badge != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(badgeBg)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(badge, style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Medium), color = HomeAgendaStyle.Muted)
            }
        }
        Text(
            text = title,
            style = CapsuleChipStyle,
            color = textColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            CheckGlyph(color = HomeAgendaStyle.Upcoming, modifier = Modifier.size(13.dp))
        }
    }
}

@Composable
private fun ConflictConfirmPopover(
    confirmation: FixedEventConfirmation,
    saving: Boolean,
    onAdjustTime: () -> Unit,
    onConfirmSave: () -> Unit,
) {
    FrostedPopoverShell(modifier = Modifier.width(326.dp).testTag("home-capture-conflict-popover")) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("与本人日程存在重叠", style = PopoverTitleStyle, color = HomeAgendaStyle.Ongoing)
            for (conflict in confirmation.conflicts.take(3)) {
                val label = if (conflict.kind == ScheduleConflictKind.WORK_SESSION) "我的工作时段" else conflict.title
                val range = "${ShanghaiTime.format(conflict.startsAt).substring(11)}–${ShanghaiTime.format(conflict.endsAt).substring(11)}"
                Text("• $label（$range）", style = PopoverSubStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "调整时间",
                    style = CapsuleChipStyle,
                    color = HomeAgendaStyle.Muted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !saving, onClick = onAdjustTime)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(HomeAgendaStyle.Ongoing)
                        .clickable(enabled = !saving, onClick = onConfirmSave)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("home-capture-conflict-confirm"),
                ) {
                    Text(if (saving) "保存中…" else "仍然保存", style = CapsuleChipStyle, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun FrostedPopoverShell(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = modifier.shadow(
            elevation = 12.dp,
            shape = shape,
            clip = false,
            ambientColor = HomeAgendaStyle.CardShadow.copy(alpha = .08f),
            spotColor = HomeAgendaStyle.CardShadow.copy(alpha = .16f),
        ),
        shape = shape,
        color = HomeAgendaStyle.CardSurface,
        border = BorderStroke(
            1.dp,
            Brush.verticalGradient(listOf(HomeAgendaStyle.CardBorderTop, HomeAgendaStyle.CardBorderBottom)),
        ),
    ) {
        Box(
            modifier = Modifier.drawBehind { drawFrostedPillSurface(radiusPx = 20.dp.toPx()) },
            content = content,
        )
    }
}

@Composable
private fun MiniPresetPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(28.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Color.White else Color(0xFFF1F3F6))
            .border(
                1.dp,
                if (selected) HomeAgendaStyle.Upcoming.copy(alpha = .40f) else HomeAgendaStyle.CardBorderTop,
                RoundedCornerShape(9.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = 11.5.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, fontFeatureSettings = "tnum"),
            color = if (selected) HomeAgendaStyle.Upcoming else HomeAgendaStyle.Muted,
            maxLines = 1,
        )
    }
}

@Composable
private fun StepArrowButton(forward: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        ChevronGlyph(forward = forward, color = HomeAgendaStyle.Muted, modifier = Modifier.size(12.dp))
    }
}

private fun DrawScope.drawFrostedPillSurface(radiusPx: Float) {
    drawRect(
        brush = Brush.verticalGradient(
            0f to HomeAgendaStyle.CardGlassTop,
            .50f to HomeAgendaStyle.CardSurface,
            1f to HomeAgendaStyle.CardGlassBottom,
        ),
    )
    drawRect(
        brush = Brush.linearGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = .34f),
                .40f to Color.White.copy(alpha = .08f),
                .80f to Color.White.copy(alpha = 0f),
            ),
            start = Offset.Zero,
            end = Offset(size.width * .60f, size.height),
        ),
    )
    val bevelInset = 1.4.dp.toPx()
    val innerRadius = (radiusPx - bevelInset).coerceAtLeast(0f)
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = .98f),
            .45f to Color.White.copy(alpha = .42f),
            1f to HomeAgendaStyle.CardInnerRefraction,
        ),
        topLeft = Offset(bevelInset, bevelInset),
        size = Size(size.width - bevelInset * 2f, size.height - bevelInset * 2f),
        cornerRadius = CornerRadius(innerRadius, innerRadius),
        style = Stroke(width = 1.dp.toPx()),
    )
}

private fun buildQuickCreateRequest(draft: QuickCaptureDraft): Result<HomeQuickCreateRequest> = runCatching {
    val trimmedTitle = draft.title.trim()
    require(trimmedTitle.isNotEmpty()) { "请先输入名称。" }
    when (draft.kind) {
        HomeQuickCreateKind.TASK -> {
            val date = draft.taskDeadlineDate
            val time = draft.taskDeadlineTimeText?.trim()?.ifEmpty { null }
            if (date == null) {
                HomeQuickCreateRequest(
                    kind = HomeQuickCreateKind.TASK,
                    title = trimmedTitle,
                    workspaceId = draft.workspaceId,
                )
            } else if (time == null) {
                require(ShanghaiTime.isValidDate(date)) { "截止日期无效。" }
                HomeQuickCreateRequest(
                    kind = HomeQuickCreateKind.TASK,
                    title = trimmedTitle,
                    taskDeadlineDate = date,
                    workspaceId = draft.workspaceId,
                )
            } else {
                val millis = requireNotNull(ShanghaiTime.parseOrNull("$date $time")) {
                    "请按 HH:mm 输入有效截止时刻。"
                }
                HomeQuickCreateRequest(
                    kind = HomeQuickCreateKind.TASK,
                    title = trimmedTitle,
                    taskDeadlineAt = millis,
                    workspaceId = draft.workspaceId,
                )
            }
        }
        HomeQuickCreateKind.EVENT -> {
            val date = draft.eventDate
            if (date == null) {
                HomeQuickCreateRequest(
                    kind = HomeQuickCreateKind.EVENT,
                    title = trimmedTitle,
                    workspaceId = draft.workspaceId,
                )
            } else {
                val startsAt = requireNotNull(ShanghaiTime.parseOrNull("$date ${draft.eventStartTimeText.trim()}")) {
                    "请按 HH:mm 输入有效开始时刻。"
                }
                val endsAt = requireNotNull(ShanghaiTime.parseOrNull("$date ${draft.eventEndTimeText.trim()}")) {
                    "请按 HH:mm 输入有效结束时刻。"
                }
                require(endsAt > startsAt) { "日程结束时间必须晚于开始时间。" }
                HomeQuickCreateRequest(
                    kind = HomeQuickCreateKind.EVENT,
                    title = trimmedTitle,
                    eventStartsAt = startsAt,
                    eventEndsAt = endsAt,
                    workspaceId = draft.workspaceId,
                )
            }
        }
    }
}

private fun formatTimeChipLabel(draft: QuickCaptureDraft, today: String): String = when (draft.kind) {
    HomeQuickCreateKind.TASK -> {
        val date = draft.taskDeadlineDate
        if (date == null) "截止时间"
        else {
            val day = shortDayLabel(date, today)
            val time = draft.taskDeadlineTimeText?.trim()?.ifEmpty { null }
            if (time == null) "$day · 全天" else "$day $time"
        }
    }
    HomeQuickCreateKind.EVENT -> {
        val date = draft.eventDate
        if (date == null) "起止时间"
        else "${shortDayLabel(date, today)} ${draft.eventStartTimeText}–${draft.eventEndTimeText}"
    }
}

private fun shortDayLabel(date: String, today: String): String = when (date) {
    today -> "今天"
    ShanghaiTime.plusDays(today, -1) -> "昨天"
    ShanghaiTime.plusDays(today, 1) -> "明天"
    ShanghaiTime.plusDays(today, 2) -> "后天"
    else -> "${date.substring(5, 7).toIntOrNull() ?: 1}/${date.substring(8, 10).toIntOrNull() ?: 1}"
}

private fun splitHourMinute(hhmm: String): Pair<String, String> {
    val parts = hhmm.trim().split(':')
    val h = (parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 0).toString().padStart(2, '0')
    val m = (parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0).toString().padStart(2, '0')
    return h to m
}

private fun buildMonthGrid(year: Int, month: Int): List<Int?> {
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val daysInMonth = when (month) {
        2 -> if (leap) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
    val firstDate = "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-01"
    val leading = (ShanghaiTime.isoWeekday(firstDate) - 1).coerceIn(0, 6)
    val total = ((leading + daysInMonth + 6) / 7) * 7
    return List(total) { index ->
        val day = index - leading + 1
        if (day in 1..daysInMonth) day else null
    }
}

@Composable
private fun PlusGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.7.dp.toPx()
        val pad = size.minDimension * .14f
        val midX = size.width / 2f
        val midY = size.height / 2f
        drawLine(color, Offset(midX, pad), Offset(midX, size.height - pad), stroke, StrokeCap.Round)
        drawLine(color, Offset(pad, midY), Offset(size.width - pad, midY), stroke, StrokeCap.Round)
    }
}

@Composable
private fun ArrowGlyph(forward: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.7.dp.toPx()
        val unit = size.minDimension / 16f
        fun pt(x: Float, y: Float) = Offset((if (forward) x else 16f - x) * unit, y * unit)
        drawLine(color, pt(3f, 8f), pt(13f, 8f), stroke, StrokeCap.Round)
        val head = Path().apply {
            val a = pt(8.5f, 3.8f); val b = pt(13f, 8f); val c = pt(8.5f, 12.2f)
            moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y)
        }
        drawPath(head, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun ChevronGlyph(forward: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.6.dp.toPx()
        val unit = size.minDimension / 14f
        fun pt(x: Float, y: Float) = Offset((if (forward) x else 14f - x) * unit, y * unit)
        val path = Path().apply {
            val a = pt(5f, 3f); val b = pt(9.2f, 7f); val c = pt(5f, 11f)
            moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y)
        }
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun CheckGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.8.dp.toPx()
        val unit = size.minDimension / 16f
        val path = Path().apply {
            moveTo(3.2f * unit, 8.4f * unit)
            lineTo(6.6f * unit, 11.8f * unit)
            lineTo(13f * unit, 4.6f * unit)
        }
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun CloseGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.5.dp.toPx()
        val pad = size.minDimension * .16f
        drawLine(color, Offset(pad, pad), Offset(size.width - pad, size.height - pad), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width - pad, pad), Offset(pad, size.height - pad), stroke, StrokeCap.Round)
    }
}

@Composable
private fun ClockGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.4.dp.toPx()
        val r = size.minDimension / 2f - stroke / 2f
        drawCircle(color, r, center, style = Stroke(stroke))
        drawLine(color, center, Offset(center.x, center.y - r * .55f), stroke, StrokeCap.Round)
        drawLine(color, center, Offset(center.x + r * .42f, center.y + r * .18f), stroke, StrokeCap.Round)
    }
}

@Composable
private fun FolderMiniGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.4.dp.toPx()
        val u = size.minDimension / 16f
        val folder = Path().apply {
            moveTo(1.8f * u, 3.8f * u)
            lineTo(6.2f * u, 3.8f * u)
            lineTo(7.6f * u, 5.4f * u)
            lineTo(14.2f * u, 5.4f * u)
            lineTo(14.2f * u, 12.8f * u)
            lineTo(1.8f * u, 12.8f * u)
            close()
        }
        drawPath(folder, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun CalendarMiniGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.4.dp.toPx()
        val u = size.minDimension / 16f
        drawRoundRect(
            color = color,
            topLeft = Offset(2f * u, 3.4f * u),
            size = Size(12f * u, 10.6f * u),
            cornerRadius = CornerRadius(2f * u),
            style = Stroke(stroke, join = StrokeJoin.Round),
        )
        drawLine(color, Offset(5.2f * u, 1.8f * u), Offset(5.2f * u, 4.8f * u), stroke, StrokeCap.Round)
        drawLine(color, Offset(10.8f * u, 1.8f * u), Offset(10.8f * u, 4.8f * u), stroke, StrokeCap.Round)
        drawLine(color, Offset(2f * u, 6.8f * u), Offset(14f * u, 6.8f * u), stroke, StrokeCap.Round)
        for (x in listOf(5.5f, 8f, 10.5f)) {
            drawCircle(color, .75f * u, Offset(x * u, 10.2f * u))
        }
    }
}

@Composable
private fun KindMiniGlyph(kind: HomeQuickCreateKind, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        when (kind) {
            HomeQuickCreateKind.EVENT -> drawCircle(color, size.minDimension / 2f, center, style = Fill)
            HomeQuickCreateKind.TASK -> {
                val r = size.minDimension / 2f
                val diamond = Path().apply {
                    moveTo(center.x, center.y - r)
                    lineTo(center.x + r, center.y)
                    lineTo(center.x, center.y + r)
                    lineTo(center.x - r, center.y)
                    close()
                }
                drawPath(diamond, color, style = Fill)
            }
        }
    }
}
