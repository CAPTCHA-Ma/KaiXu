package com.kaixu.desktop.presentation.navigation

/** 导航目的地标识，与 AppGraph 的对象依赖图是两回事。 */
object DesktopDestination {
    const val TASK_EDITOR = "task-editor"
    const val SETTINGS = "settings"
    const val WORKSPACES = "workspaces"
    const val SCHEDULE = "schedule"
    const val RECORD = "record/{kind}/{id}"
    const val RESOURCE = "resource/{id}"
}
