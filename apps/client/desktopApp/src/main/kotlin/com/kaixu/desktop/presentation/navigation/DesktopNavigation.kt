package com.kaixu.desktop.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.kaixu.app.di.AppGraph
import com.kaixu.desktop.presentation.ui.settings.SettingsScreen
import com.kaixu.desktop.presentation.ui.tasks.TaskEditorRoute
import com.kaixu.presentation.viewmodel.tasks.TaskEditorViewModel
import com.kaixu.presentation.viewmodel.workspaces.WorkspaceViewModel
import com.kaixu.data.datasource.WorkspaceDataSource
import com.kaixu.desktop.presentation.ui.workspaces.WorkspaceRoute

/** 导航层决定显示哪个页面，并为各导航条目取得页面 ViewModel。 */
@Composable
fun DesktopNavigation(graph: AppGraph, modifier: Modifier = Modifier, workspaces: WorkspaceDataSource? = null) {
    val navController = rememberNavController()
    val current by navController.currentBackStackEntryAsState()
    Row(modifier.fillMaxSize()) {
        if (workspaces != null) {
            Column(Modifier.width(180.dp).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("KaiXu", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 20.dp))
                for ((route, label) in listOf(DesktopDestination.TASK_EDITOR to "任务", DesktopDestination.WORKSPACES to "工作区")) {
                    NavigationDrawerItem(
                        label = { Text(label) }, selected = current?.destination?.route == route,
                        modifier = Modifier.testTag("nav-$route"),
                        onClick = {
                            navController.navigate(route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
            VerticalDivider()
        }
        NavHost(
            navController = navController,
            startDestination = DesktopDestination.TASK_EDITOR,
            modifier = Modifier.weight(1f),
        ) {
            composable(DesktopDestination.TASK_EDITOR) { entry ->
                // entry 是 NavBackStackEntry：同一条目重组时复用 VM，出栈时清理。
                val editor = viewModel<TaskEditorViewModel>(viewModelStoreOwner = entry) {
                    TaskEditorViewModel(graph.createTask, graph.observeTasks, graph.refreshTasks)
                }
                TaskEditorRoute(
                    viewModel = editor,
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
            if (workspaces != null) {
                composable(DesktopDestination.WORKSPACES) { entry ->
                    val workspaceViewModel = viewModel<WorkspaceViewModel>(viewModelStoreOwner = entry) {
                        WorkspaceViewModel(workspaces)
                    }
                    WorkspaceRoute(workspaceViewModel)
                }
            }
        }
    }
}
