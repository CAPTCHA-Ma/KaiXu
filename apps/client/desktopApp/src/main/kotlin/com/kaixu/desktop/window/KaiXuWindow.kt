package com.kaixu.desktop.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.kaixu.desktop.app.DesktopApp

/** Windows 宿主。保留原生装饰，由 JBR 扩展客户区，不使用 undecorated。 */
@Composable
fun KaiXuWindow(onCloseRequest: () -> Unit) {
    Window(
        onCloseRequest = onCloseRequest,
        title = "KaiXu",
        state = rememberWindowState(width = 1180.dp, height = 800.dp),
    ) {
        val titleBar = remember(window) { WindowsTitleBarState() }
        DisposableEffect(window, titleBar) {
            // 非 Windows 仅供编译与业务测试；不实现其他平台的窗口适配。
            val detach = if (System.getProperty("os.name").startsWith("Windows")) {
                titleBar.attach(window)
            } else null
            onDispose { detach?.invoke() }
        }
        DesktopApp(titleBar = titleBar)
    }
}
