package com.kaixu.desktop.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily

// Windows 默认回退字体可能让中文与拉丁字母的笔画观感不一致。
// 使用系统字体，无需随应用分发字体文件；其他平台保留原有默认字体。
@OptIn(ExperimentalTextApi::class)
private val desktopTypography = Typography().let { base ->
    if (!System.getProperty("os.name").startsWith("Windows")) return@let base
    val family = FontFamily("Microsoft YaHei UI")
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family),
    )
}

/** 本端主题入口；颜色、字体和控件样式可在本模块独立演进。 */
@Composable
fun KaiXuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        typography = desktopTypography,
        colorScheme = lightColorScheme(
            primary = Color(0xFF3157C8),
            background = Color.White,
            surface = Color.White,
            onSurface = Color(0xFF19243B),
            secondaryContainer = Color(0xFFEAF0FF),
            onSecondaryContainer = Color(0xFF3157C8),
        ),
        content = content,
    )
}
