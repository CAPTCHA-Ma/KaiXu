package com.kaixu.desktop.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.kaixu.desktop.presentation.navigation.DesktopDestination

/** 同一个目标同时用于预览和点击，调用方无需另写预览名称或跳转路由。 */
@Composable
internal fun <D : DesktopDestination> DesktopNavigationButton(
    destination: D,
    tag: String,
    onNavigate: (D) -> Unit,
    onPreview: (source: String, destination: DesktopDestination?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = destination.title,
    selected: Boolean = false,
    enabled: Boolean = true,
    content: @Composable (foreground: Color) -> Unit,
) {
    DesktopChromeButton(
        label = label,
        tag = tag,
        onClick = { onNavigate(destination) },
        modifier = modifier,
        selected = selected,
        enabled = enabled,
        previewDestination = destination,
        onPreview = onPreview,
        content = content,
    )
}
