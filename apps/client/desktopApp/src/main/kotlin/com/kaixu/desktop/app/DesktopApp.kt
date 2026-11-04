package com.kaixu.desktop.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kaixu.desktop.presentation.navigation.DesktopNavigation
import com.kaixu.desktop.presentation.theme.KaiXuTheme
import com.kaixu.app.di.AppGraph

/** 本端根界面：取得宿主依赖、应用主题，并进入本端导航。 */
@Composable
fun DesktopApp(graph: AppGraph? = null) {
    val activeGraph = graph ?: viewModel { DesktopAppContainer() }.graph
    KaiXuTheme {
        Surface(Modifier.fillMaxSize()) {
            DesktopNavigation(graph = activeGraph)
        }
    }
}
