package com.kaixu.desktop

import androidx.compose.ui.window.application
import com.kaixu.desktop.window.KaiXuWindow

/** Windows / JVM 桌面宿主：窗口生命周期与桌面页面分开。 */
fun main() = application {
    KaiXuWindow(onCloseRequest = ::exitApplication)
}

