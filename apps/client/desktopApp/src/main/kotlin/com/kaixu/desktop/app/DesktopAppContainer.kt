package com.kaixu.desktop.app

import androidx.lifecycle.ViewModel
import com.kaixu.app.di.AppGraph
import com.kaixu.data.datasource.local.RoomTaskDataSource
import com.kaixu.data.datasource.local.database.openDesktopDatabase

/** 宿主范围的依赖容器；页面草稿由导航条目的 ViewModel 管理。 */
class DesktopAppContainer : ViewModel() {
    private val database = openDesktopDatabase()
    val graph = AppGraph(source = RoomTaskDataSource(database.taskDao()))

    override fun onCleared() {
        database.close()
    }
}
