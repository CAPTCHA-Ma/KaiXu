package com.kaixu.desktop.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.kaixu.desktop.presentation.navigation.DesktopDestination
import com.kaixu.desktop.presentation.theme.DesktopChromeColors as Colors

/** 标题栏和侧栏复用相同的圆角、鼠标反馈与选中状态。 */
@Composable
internal fun DesktopChromeButton(
    label: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    previewDestination: DesktopDestination? = null,
    onPreview: (source: String, destination: DesktopDestination?) -> Unit = { _, _ -> },
    content: @Composable (foreground: Color) -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    var previewConsumed by remember { mutableStateOf(false) }
    val previewCallback by rememberUpdatedState(onPreview)
    DisposableEffect(hovered, enabled, tag, previewDestination) {
        if (!hovered) previewConsumed = false
        val showingPreview = hovered && enabled && !previewConsumed && previewDestination != null
        if (showingPreview) previewCallback(tag, previewDestination)
        onDispose { if (showingPreview) previewCallback(tag, null) }
    }
    val active = enabled && (selected || hovered || pressed)
    val foreground = (if (enabled && selected) Colors.Color3 else Colors.Color2)
        .copy(alpha = if (enabled) 1f else .35f)
    Box(
        modifier.clip(RoundedCornerShape(Colors.ButtonCornerRadius))
            .background(if (active) Colors.Color1 else Colors.Color0)
            .testTag(tag)
            .semantics { contentDescription = label; this.selected = selected }
            .hoverable(interactions, enabled)
            .clickable(interactions, indication = null, enabled = enabled, role = Role.Button) {
                // 点击消耗本次预览，鼠标仍停在按钮上时不覆盖已切换的页面标题。
                previewConsumed = true
                previewCallback(tag, null)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        content(foreground)
    }
}
