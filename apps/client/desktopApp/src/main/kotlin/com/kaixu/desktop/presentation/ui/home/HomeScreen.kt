package com.kaixu.desktop.presentation.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** 首页入口；日程汇总、待办和动态区域将在后续接入。 */
@Composable
fun HomeScreen() {
    Box(Modifier.fillMaxSize().testTag("home-screen"))
}
