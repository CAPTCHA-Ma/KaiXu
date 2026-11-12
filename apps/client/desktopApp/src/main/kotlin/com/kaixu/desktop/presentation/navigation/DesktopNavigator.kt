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
        DesktopLocation(DesktopDestination.Tasks, DesktopDestination.Tasks)
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
            DesktopDestination.Settings -> null
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
        // 任务页作为稳定根条目保留草稿；工作区、日程复用已有的保存/恢复机制。
        // 带 ID 的记录详情重新读取，不按目的地类型恢复，避免不同记录共用旧参数。
        val currentRoute = controller.currentDestination?.route
        val retainCurrent = currentRoute == DesktopDestination.WORKSPACES || currentRoute == DesktopDestination.SCHEDULE
        if (target.destination == DesktopDestination.Tasks) {
            controller.popBackStack(DesktopDestination.TASK_EDITOR, inclusive = false, saveState = retainCurrent)
        } else {
            controller.navigate(target.destination.route) {
                popUpTo(DesktopDestination.TASK_EDITOR) { saveState = retainCurrent }
                restoreState = target.destination == DesktopDestination.Workspaces || target.destination == DesktopDestination.Schedule
                launchSingleTop = true
            }
        }
    }
}
