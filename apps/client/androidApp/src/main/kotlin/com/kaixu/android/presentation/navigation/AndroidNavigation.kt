package com.kaixu.android.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kaixu.app.di.AppGraph
import com.kaixu.android.presentation.ui.settings.SettingsScreen
import com.kaixu.android.presentation.ui.tasks.TaskEditorRoute
import com.kaixu.presentation.viewmodel.tasks.TaskEditorViewModel

/** 导航层决定显示哪个页面，并为各导航条目取得页面 ViewModel。 */
@Composable
fun AndroidNavigation(graph: AppGraph, modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = AndroidDestination.TASK_EDITOR,
        modifier = modifier,
    ) {
        composable(AndroidDestination.TASK_EDITOR) { entry ->
            // entry 是 NavBackStackEntry：同一条目重组时复用 VM，出栈时清理。
            val editor = viewModel<TaskEditorViewModel>(viewModelStoreOwner = entry) {
                TaskEditorViewModel(graph.createTask, graph.observeTasks, graph.refreshTasks)
            }
            TaskEditorRoute(
                viewModel = editor,
                onOpenSettings = {
                    navController.navigate(AndroidDestination.SETTINGS) {
                        launchSingleTop = true
                    }
                },
                onSimulateSaveFailure = graph::simulateNextSaveFailure,
            )
        }
        composable(AndroidDestination.SETTINGS) {
            // 纯说明页没有业务状态，不需要空壳 ViewModel。
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
