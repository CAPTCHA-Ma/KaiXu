package com.kaixu.desktop.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kaixu.desktop.presentation.navigation.DesktopNavigation
import com.kaixu.desktop.presentation.theme.KaiXuTheme
import com.kaixu.app.di.AppGraph
import com.kaixu.data.datasource.WorkspaceDataSource
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.desktop.window.WindowsTitleBarState
import androidx.compose.runtime.remember

/** 本端根界面：取得宿主依赖、应用主题，并进入本端导航。 */
@Composable
fun DesktopApp(
    graph: AppGraph? = null,
    workspaces: WorkspaceDataSource? = null,
    localDataSources: LocalDataSources? = null,
    titleBar: WindowsTitleBarState = remember { WindowsTitleBarState() },
) {
    val container = if (graph == null) viewModel { DesktopAppContainer() } else null
    val activeGraph = graph ?: requireNotNull(container).graph
    val sources = localDataSources ?: container?.localDataSources
    val workspaceSource = workspaces ?: sources?.workspaces
    KaiXuTheme {
        Surface(Modifier.fillMaxSize()) {
            DesktopNavigation(graph = activeGraph, workspaces = workspaceSource, sources = sources, titleBar = titleBar)
        }
    }
}
