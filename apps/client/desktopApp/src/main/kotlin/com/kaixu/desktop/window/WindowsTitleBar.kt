package com.kaixu.desktop.window

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp
import com.jetbrains.JBR
import com.jetbrains.WindowDecorations
import com.kaixu.desktop.presentation.theme.DesktopChromeColors
import java.awt.Color
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame

/** 每个真实窗口单独持有；原生细节不会进入业务页面或导航。 */
@Stable
class WindowsTitleBarState {
    var leftInset by mutableStateOf(0.dp)
        private set
    var rightInset by mutableStateOf(0.dp)
        private set
    private var buttonsBounds = Rect.Zero
    private var nativeTitleBar: WindowDecorations.CustomTitleBar? = null

    fun updateButtonsBounds(bounds: Rect) { buttonsBounds = bounds }

    /** 在 Initial 阶段为本次原生事件区分按钮和可拖动背景，不消费 Compose 点击。 */
    fun hitTest(event: PointerEvent) {
        if (event.type == PointerEventType.Exit || event.type == PointerEventType.Scroll) return
        val position = event.changes.firstOrNull()?.position ?: return
        nativeTitleBar?.forceHitTest(buttonsBounds.contains(position))
    }

    internal fun attach(window: JFrame): () -> Unit {
        check(JBR.isWindowDecorationsSupported()) {
            "Windows 自定义标题栏需要支持 WindowDecorations 的 JBRSDK 21，请检查桌面运行配置。"
        }
        val decorations = requireNotNull(JBR.getWindowDecorations())
        val bar = decorations.createCustomTitleBar().apply {
            // JBR/AWT 使用窗口逻辑坐标，Compose 的 dp 同步到相同的逻辑高度。
            height = DesktopChromeColors.Height.value
            putProperty("controls.visible", true)
            putProperty("controls.dark", false)
            for (state in listOf("normal", "inactive", "disabled", "hovered", "pressed")) {
                val background = if (state == "hovered" || state == "pressed") {
                    DesktopChromeColors.Color1
                } else DesktopChromeColors.Color0
                putProperty("controls.background.$state", Color(background.toArgb(), true))
                putProperty("controls.foreground.$state", Color(DesktopChromeColors.Color2.toArgb(), true))
            }
        }
        nativeTitleBar = bar
        decorations.setCustomTitleBar(window, bar)
        fun updateInsets() {
            leftInset = bar.leftInset.dp
            rightInset = bar.rightInset.dp
        }
        val componentListener = object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = updateInsets()
            override fun componentShown(event: ComponentEvent) = updateInsets()
            override fun componentMoved(event: ComponentEvent) = updateInsets()
        }
        val windowListener = object : WindowAdapter() {
            override fun windowStateChanged(event: WindowEvent) = updateInsets()
        }
        window.addComponentListener(componentListener)
        window.addWindowStateListener(windowListener)
        updateInsets()
        return {
            window.removeComponentListener(componentListener)
            window.removeWindowStateListener(windowListener)
            decorations.setCustomTitleBar(window, null)
            nativeTitleBar = null
            leftInset = 0.dp
            rightInset = 0.dp
        }
    }
}
