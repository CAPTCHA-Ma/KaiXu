package com.kaixu.desktop.presentation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 窗口框架样式，标题栏、侧栏与 JBR 原生窗口按钮共用。 */
object DesktopChromeColors {
    val Color0 = Color(0xFFF3F3F3)
    val Color1 = Color(0xFFDEDEDE)
    val Color2 = Color(0xFF656565)
    val Color3 = Color.Black
    val Height = 45.dp
    val ButtonHeight = Height * .7f
    val ButtonCornerRadius = 8.dp
    val SidebarButtonSize = 36.dp
    val SidebarWidth = SidebarButtonSize + 16.dp
}
