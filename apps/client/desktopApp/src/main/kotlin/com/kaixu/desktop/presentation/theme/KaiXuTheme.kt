package com.kaixu.desktop.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 本端主题入口；颜色、字体和控件样式可在本模块独立演进。 */
@Composable
fun KaiXuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF3157C8),
            background = Color(0xFFF5F7FB),
            surface = Color.White,
            onSurface = Color(0xFF19243B),
            secondaryContainer = Color(0xFFEAF0FF),
            onSecondaryContainer = Color(0xFF3157C8),
        ),
        content = content,
    )
}
