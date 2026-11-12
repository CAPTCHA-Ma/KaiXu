package com.kaixu.desktop.presentation.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController

internal data class DesktopLocation(val route: String, val section: String?)

/** 窗口级访问历史：新跳转丢弃前进分支，重复点击当前位置不增加记录。 */
@Stable
internal class DesktopHistory {
    private val entries = mutableStateListOf(
        DesktopLocation(DesktopDestination.TASK_EDITOR, DesktopDestination.TASK_EDITOR)
    )
    var index by mutableIntStateOf(0)
        private set
    val current get() = entries[index]
    val previous get() = entries.getOrNull(index - 1)
    val next get() = entries.getOrNull(index + 1)
    val canGoBack get() = previous != null
    val canGoForward get() = next != null

    fun visit(location: DesktopLocation) {
        if (current.route == location.route) return
        while (entries.lastIndex > index) entries.removeAt(entries.lastIndex)
        entries.add(location)
        index = entries.lastIndex
    }

    fun back() { if (canGoBack) index-- }
    fun forward() { if (canGoForward) index++ }
}

/** 所有主页面、设置和记录详情的跳转统一经过这里。 */
@Stable
internal class DesktopNavigator(private val controller: NavHostController) {
    val history = DesktopHistory()

    fun openSection(route: String) = open(DesktopLocation(route, route))
    fun openSettings() = open(DesktopLocation(DesktopDestination.SETTINGS, null))
    fun openRecord(route: String) = open(DesktopLocation(route, history.current.section))

    fun back() {
        val target = history.previous ?: return
        show(target)
        history.back()
    }

    fun forward() {
        val target = history.next ?: return
        show(target)
        history.forward()
    }

    private fun open(location: DesktopLocation) {
        if (history.current.route == location.route) return
        show(location)
        history.visit(location)
    }

    private fun show(target: DesktopLocation) {
        // 任务页作为稳定根条目保留草稿；工作区、日程复用已有的保存/恢复机制。
        // 带 ID 的记录详情重新读取，不按目的地类型恢复，避免不同记录共用旧参数。
        val currentRoute = controller.currentDestination?.route
        val retainCurrent = currentRoute == DesktopDestination.WORKSPACES || currentRoute == DesktopDestination.SCHEDULE
        if (target.route == DesktopDestination.TASK_EDITOR) {
            controller.popBackStack(DesktopDestination.TASK_EDITOR, inclusive = false, saveState = retainCurrent)
        } else {
            controller.navigate(target.route) {
                popUpTo(DesktopDestination.TASK_EDITOR) { saveState = retainCurrent }
                restoreState = target.route == DesktopDestination.WORKSPACES || target.route == DesktopDestination.SCHEDULE
                launchSingleTop = true
            }
        }
    }
}
