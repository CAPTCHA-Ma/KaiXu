package com.kaixu.desktop.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kaixu.desktop.window.WindowsTitleBarState
import com.kaixu.desktop.presentation.navigation.DesktopDestination

/** 标题栏固定在页面之外；主内容继续由现有导航提供。 */
@Composable
fun DesktopShell(
    titleBar: WindowsTitleBarState,
    currentDestination: DesktopDestination,
    previewDestination: DesktopDestination?,
    backDestination: DesktopDestination?,
    forwardDestination: DesktopDestination?,
    onNavigate: (DesktopDestination) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onPreview: (source: String, destination: DesktopDestination?) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        DesktopTitleBar(
            host = titleBar,
            currentDestination = currentDestination,
            previewDestination = previewDestination,
            backDestination = backDestination,
            forwardDestination = forwardDestination,
            onNavigate = onNavigate,
            onBack = onBack,
            onForward = onForward,
            onPreview = onPreview,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}
