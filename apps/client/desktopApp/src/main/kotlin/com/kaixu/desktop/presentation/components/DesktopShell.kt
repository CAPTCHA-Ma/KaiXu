package com.kaixu.desktop.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kaixu.desktop.window.WindowsTitleBarState

/** 标题栏固定在页面之外；主内容继续由现有导航提供。 */
@Composable
fun DesktopShell(
    titleBar: WindowsTitleBarState,
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
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        DesktopTitleBar(
            titleBar, settingsSelected, canGoBack, canGoForward, onSettings, onBack, onForward,
            currentTitle, previewTitle, backTitle, forwardTitle, onPreview,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}
