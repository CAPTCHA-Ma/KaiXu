package com.kaixu.desktop.presentation.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController

internal data class DesktopLocation(
    val destination: DesktopDestination,
    val section: DesktopDestination.Section?,
)

/** 窗口级访问历史：新跳转丢弃前进分支，重复点击当前位置不增加记录。 */
@Stable
internal class DesktopHistory {
    private val entries = mutableStateListOf(
        DesktopLocation(DesktopDestination.Home, null)
    )
    var index by mutableIntStateOf(0)
        private set
    val current get() = entries[index]
    val previous get() = entries.getOrNull(index - 1)
    val next get() = entries.getOrNull(index + 1)
    val canGoBack get() = previous != null
    val canGoForward get() = next != null

    fun visit(location: DesktopLocation) {
        if (current.destination == location.destination) return
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

    fun open(destination: DesktopDestination) {
        val section = when (destination) {
            is DesktopDestination.Section -> destination
            DesktopDestination.Home, DesktopDestination.Settings -> null
            else -> history.current.section
        }
        val location = DesktopLocation(destination, section)
        if (history.current.destination == destination) return
        show(location)
        history.visit(location)
    }

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

    private fun show(target: DesktopLocation) {
        // 首页作为稳定根条目；任务、工作区、日程保存条目和 ViewModel，保留草稿与筛选。
        // 带 ID 的记录详情重新读取，不按目的地类型恢复，避免不同记录共用旧参数。
        val currentRoute = controller.currentDestination?.route
        val retainCurrent = currentRoute == DesktopDestination.TASK_EDITOR ||
            currentRoute == DesktopDestination.WORKSPACES || currentRoute == DesktopDestination.SCHEDULE
        if (target.destination == DesktopDestination.Home) {
            controller.popBackStack(DesktopDestination.HOME, inclusive = false, saveState = retainCurrent)
        } else {
            controller.navigate(target.destination.route) {
                popUpTo(DesktopDestination.HOME) { saveState = retainCurrent }
                restoreState = target.destination is DesktopDestination.Section
                launchSingleTop = true
            }
        }
    }
}
