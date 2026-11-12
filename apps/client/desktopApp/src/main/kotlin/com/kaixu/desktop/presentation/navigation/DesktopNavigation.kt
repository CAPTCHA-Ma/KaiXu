package com.kaixu.desktop.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.*
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
import com.kaixu.desktop.presentation.components.DesktopShell
import com.kaixu.desktop.presentation.components.DesktopSidebar
import com.kaixu.desktop.window.WindowsTitleBarState

/** 导航层决定显示哪个页面，并为各导航条目取得页面 ViewModel。 */
@Composable
fun DesktopNavigation(
    graph: AppGraph,
    modifier: Modifier = Modifier,
    workspaces: WorkspaceDataSource? = null,
    sources: LocalDataSources? = null,
    titleBar: WindowsTitleBarState = remember { WindowsTitleBarState() },
) {
    val navController = rememberNavController()
    val navigator = remember(navController) { DesktopNavigator(navController) }
    val history = navigator.history
    val navigationScope = rememberCoroutineScope()
    val current by navController.currentBackStackEntryAsState()
    val activeSection = history.current.section
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }
    val onPreview: (String, String?) -> Unit = { source, title ->
        if (title != null) preview = source to title
        else if (preview?.first == source) preview = null
    }
    val openRecord: (LocalRecordTarget) -> Unit = { target ->
        navigator.openRecord("record/${target.kind.name}/${target.id}")
    }
    DesktopShell(
        titleBar = titleBar,
        settingsSelected = current?.destination?.route == DesktopDestination.SETTINGS,
        canGoBack = history.canGoBack,
        canGoForward = history.canGoForward,
        onSettings = navigator::openSettings,
        onBack = navigator::back,
        onForward = navigator::forward,
        currentTitle = history.current.caption,
        previewTitle = preview?.second,
        backTitle = history.previous?.caption,
        forwardTitle = history.next?.caption,
        onPreview = onPreview,
        modifier = modifier,
    ) {
        Row(Modifier.fillMaxSize()) {
            if (workspaces != null) {
                DesktopSidebar(
                    activeSection = activeSection,
                    showSchedule = sources != null,
                    onOpenSection = navigator::openSection,
                    onPreview = onPreview,
                )
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
                        onOpenSettings = navigator::openSettings,
                    )
                }
                composable(DesktopDestination.SETTINGS) {
                    // 纯说明页没有业务状态，不需要空壳 ViewModel。
                    SettingsScreen(
                        onBack = navigator::back,
                        backLabel = when (history.previous?.route) {
                            DesktopDestination.TASK_EDITOR -> "返回任务"
                            DesktopDestination.WORKSPACES -> "返回工作区"
                            DesktopDestination.SCHEDULE -> "返回日程"
                            else -> "返回上一页"
                        },
                    )
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
                                                navigator.openRecord("resource/$id")
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
                        ResourceRecordRoute(id, sources, onBack = navigator::back)
                    }
                    composable(DesktopDestination.RECORD) { entry ->
                        val target = requireNotNull(entry.arguments).read {
                            LocalRecordTarget(RecordKind.valueOf(getString("kind")), getString("id"))
                        }
                        LocalRecordRoute(target, sources, onBack = navigator::back)
                    }
                }
            }
        }
    }
}

private val DesktopLocation.caption: String
    get() = when {
        route == DesktopDestination.TASK_EDITOR -> "任务"
        route == DesktopDestination.SCHEDULE -> "日程"
        route == DesktopDestination.WORKSPACES -> "工作区"
        route == DesktopDestination.SETTINGS -> "设置"
        route.startsWith("resource/") -> "资源详情"
        route.startsWith("record/TASK/") -> "任务详情"
        route.startsWith("record/EVENT/") -> "事件详情"
        else -> "KaiXu"
    }
