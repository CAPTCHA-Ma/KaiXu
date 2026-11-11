package com.kaixu.desktop.app

import androidx.lifecycle.ViewModel
import com.kaixu.app.di.AppGraph
import com.kaixu.app.di.LocalSchedulingGraph
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.data.datasource.local.database.openDesktopDatabase
import java.time.Instant
import java.time.ZoneId

/** 宿主范围的依赖容器；页面草稿由导航条目的 ViewModel 管理。 */
class DesktopAppContainer : ViewModel() {
    private val database = openDesktopDatabase()
    val localDataSources = LocalDataSources(database)
    val graph = AppGraph(source = localDataSources.tasks)
    val scheduling = LocalSchedulingGraph(localDataSources, LOCAL_ACTOR_ID) { millis ->
        Instant.ofEpochMilli(millis).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString()
    }

    override fun onCleared() {
        database.close()
    }
}
