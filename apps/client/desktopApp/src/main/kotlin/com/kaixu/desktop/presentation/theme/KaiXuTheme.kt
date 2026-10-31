package com.kaixu.desktop.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/** 本端主题入口；颜色、字体和控件样式可在本模块独立演进。 */
@Composable
fun KaiXuTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
