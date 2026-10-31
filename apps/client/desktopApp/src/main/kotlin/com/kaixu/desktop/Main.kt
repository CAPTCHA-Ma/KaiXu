package com.kaixu.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kaixu.desktop.app.DesktopApp

/** Windows / JVM 桌面宿主：窗口生命周期与桌面页面分开。 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "KaiXu 个人任务示例",
        state = rememberWindowState(width = 900.dp, height = 720.dp),
    ) {
        DesktopApp()
    }
}

