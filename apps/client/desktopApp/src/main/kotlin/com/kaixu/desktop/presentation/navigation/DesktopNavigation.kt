package com.kaixu.desktop.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.savedstate.read
import com.kaixu.app.di.AppGraph
import com.kaixu.desktop.presentation.ui.settings.SettingsScreen
import com.kaixu.desktop.presentation.ui.tasks.TaskEditorRoute
import com.kaixu.presentation.viewmodel.tasks.TaskEditorViewModel
import com.kaixu.presentation.viewmodel.workspaces.WorkspaceViewModel
import com.kaixu.data.datasource.WorkspaceDataSource
import com.kaixu.desktop.presentation.ui.workspaces.WorkspaceRoute
import com.kaixu.desktop.presentation.ui.workspaces.WorkspaceOverviewRoute
import com.kaixu.desktop.presentation.ui.workspaces.currentWorkspaceWindow
import com.kaixu.desktop.presentation.ui.records.LocalRecordRoute
import com.kaixu.desktop.presentation.ui.records.LocalRecordTarget
import com.kaixu.desktop.presentation.ui.records.RecordKind
import com.kaixu.desktop.presentation.ui.resources.ResourceRecordRoute
import com.kaixu.desktop.presentation.ui.resources.WorkspaceResourcesSection
import com.kaixu.desktop.presentation.ui.scheduling.LocalScheduleRoute
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.domain.usecase.workspaces.WorkspaceOverviewUseCase
import com.kaixu.presentation.viewmodel.workspaces.WorkspaceOverviewViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 导航层决定显示哪个页面，并为各导航条目取得页面 ViewModel。 */
@Composable
fun DesktopNavigation(
    graph: AppGraph,
    modifier: Modifier = Modifier,
    workspaces: WorkspaceDataSource? = null,
    sources: LocalDataSources? = null,
) {
    val navController = rememberNavController()
    val navigationScope = rememberCoroutineScope()
    val current by navController.currentBackStackEntryAsState()
    val activeSection = if (current?.destination?.route in listOf(DesktopDestination.RECORD, DesktopDestination.RESOURCE)) {
        navController.previousBackStackEntry?.destination?.route
    } else current?.destination?.route
    val openRecord: (LocalRecordTarget) -> Unit = { target ->
        navController.navigate("record/${target.kind.name}/${target.id}") { launchSingleTop = true }
    }
    Row(modifier.fillMaxSize()) {
        if (workspaces != null) {
            Column(Modifier.width(180.dp).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("KaiXu", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 20.dp))
                val sections = buildList {
                    add(DesktopDestination.TASK_EDITOR to "任务")
                    if (sources != null) add(DesktopDestination.SCHEDULE to "日程")
                    add(DesktopDestination.WORKSPACES to "工作区")
                }
                for ((route, label) in sections) {
                    NavigationDrawerItem(
                        label = { Text(label) }, selected = activeSection == route,
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
                    TaskEditorViewModel(graph.taskUseCases)
                }
                TaskEditorRoute(
                    viewModel = editor,
                    onOpenTask = if (sources == null) null else { id -> openRecord(LocalRecordTarget(RecordKind.TASK, id)) },
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
                    val overviewViewModel = if (sources != null) {
                        viewModel<WorkspaceOverviewViewModel>(viewModelStoreOwner = entry) {
                            WorkspaceOverviewViewModel(
                                WorkspaceOverviewUseCase(sources.tasks, sources.fixedEvents, sources.workSessions),
                                LOCAL_ACTOR_ID, ::currentWorkspaceWindow,
                            )
                        }
                    } else null
                    WorkspaceRoute(
                        workspaceViewModel,
                        overviewContent = overviewViewModel?.let { overview ->
                            { space -> WorkspaceOverviewRoute(space.id, overview, openRecord) }
                        },
                        resourcesContent = sources?.let { localSources ->
                            { space, summary, openAll ->
                                WorkspaceResourcesSection(
                                    space, localSources,
                                    onOpenResource = { id ->
                                        navigationScope.launch(Dispatchers.Main.immediate) {
                                            navController.navigate("resource/$id") { launchSingleTop = true }
                                        }
                                    },
                                    onOpenAll = openAll, summary = summary,
                                )
                            }
                        },
                    )
                }
            }
            if (sources != null) {
                composable(DesktopDestination.SCHEDULE) {
                    LocalScheduleRoute(sources.fixedEvents, onOpenEvent = { id ->
                        openRecord(LocalRecordTarget(RecordKind.EVENT, id))
                    })
                }
                composable(DesktopDestination.RESOURCE) { entry ->
                    val id = requireNotNull(entry.arguments).read { getString("id") }
                    ResourceRecordRoute(id, sources, onBack = { navController.popBackStack() })
                }
                composable(DesktopDestination.RECORD) { entry ->
                    val target = requireNotNull(entry.arguments).read {
                        LocalRecordTarget(RecordKind.valueOf(getString("kind")), getString("id"))
                    }
                    LocalRecordRoute(target, sources, onBack = { navController.popBackStack() })
                }
            }
        }
    }
}
