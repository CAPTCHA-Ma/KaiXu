package com.kaixu.desktop.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kaixu.desktop.presentation.theme.DesktopChromeColors as Colors
import com.kaixu.desktop.window.WindowsTitleBarState
import com.kaixu.desktop.presentation.navigation.DesktopDestination

/** 标题栏与侧栏共用背景；所有页面显示在统一的白色圆角面板内。 */
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
    sidebar: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val panelShape = RoundedCornerShape(Colors.ContentCornerRadius)
    Column(modifier.fillMaxSize().background(Colors.Color0).testTag("desktop-shell")) {
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
        Row(Modifier.weight(1f).fillMaxWidth().padding(end = Colors.ContentInset, bottom = Colors.ContentInset)) {
            sidebar()
            Surface(
                modifier = Modifier.weight(1f).fillMaxSize()
                    .shadow(
                        elevation = Colors.ContentElevation,
                        shape = panelShape,
                        clip = false,
                        ambientColor = Colors.ContentAmbientShadow,
                        spotColor = Colors.ContentSpotShadow,
                    )
                    .testTag("desktop-content-panel"),
                shape = panelShape,
                color = Colors.ContentBackground,
                border = BorderStroke(1.dp, Colors.ContentBorder),
            ) { content() }
        }
    }
}
