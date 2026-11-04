package com.kaixu.desktop.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kaixu.app.di.AppGraph
import com.kaixu.desktop.presentation.ui.settings.SettingsScreen
import com.kaixu.desktop.presentation.ui.tasks.TaskEditorRoute
import com.kaixu.presentation.viewmodel.tasks.TaskEditorViewModel

/** 导航层决定显示哪个页面，并为各导航条目取得页面 ViewModel。 */
@Composable
fun DesktopNavigation(graph: AppGraph, modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = DesktopDestination.TASK_EDITOR,
        modifier = modifier,
    ) {
        composable(DesktopDestination.TASK_EDITOR) { entry ->
            // entry 是 NavBackStackEntry：同一条目重组时复用 VM，出栈时清理。
            val editor = viewModel<TaskEditorViewModel>(viewModelStoreOwner = entry) {
                TaskEditorViewModel(graph.createTask, graph.observeTasks, graph.refreshTasks)
            }
            TaskEditorRoute(
                viewModel = editor,
                onSimulateSaveFailure = if (graph.isMemoryDemo) graph::simulateNextSaveFailure else null,
                onOpenSettings = {
                    navController.navigate(DesktopDestination.SETTINGS) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(DesktopDestination.SETTINGS) {
            // 纯说明页没有业务状态，不需要空壳 ViewModel。
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
