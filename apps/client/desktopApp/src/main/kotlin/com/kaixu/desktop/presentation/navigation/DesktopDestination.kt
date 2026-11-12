package com.kaixu.desktop.presentation.navigation

/** 页面是路由和名称的唯一来源；导航、悬停和历史共用同一个目的地。 */
sealed class DesktopDestination(val title: String) {
    abstract val route: String

    sealed class Section(override val route: String, title: String) : DesktopDestination(title)

    data object Tasks : Section(TASK_EDITOR, "任务")
    data object Schedule : Section(SCHEDULE, "日程")
    data object Workspaces : Section(WORKSPACES, "工作区")
    data object Settings : DesktopDestination("设置") {
        override val route = SETTINGS
    }
    data class TaskRecord(val id: String) : DesktopDestination("任务详情") {
        override val route = "record/TASK/$id"
    }
    data class EventRecord(val id: String) : DesktopDestination("事件详情") {
        override val route = "record/EVENT/$id"
    }
    data class SessionRecord(val id: String) : DesktopDestination("工作时段详情") {
        override val route = "record/SESSION/$id"
    }
    data class ResourceRecord(val id: String) : DesktopDestination("资源详情") {
        override val route = "resource/$id"
    }

    companion object {
        // NavHost 的路由模板；带参数的实际页面使用上面的类型构造。
        const val TASK_EDITOR = "task-editor"
        const val SETTINGS = "settings"
        const val WORKSPACES = "workspaces"
        const val SCHEDULE = "schedule"
        const val RECORD = "record/{kind}/{id}"
        const val RESOURCE = "resource/{id}"
    }
}
