package com.kaixu.desktop.app

import androidx.lifecycle.ViewModel
import com.kaixu.app.di.AppGraph
import com.kaixu.app.di.LocalSchedulingGraph
import com.kaixu.core.platform.LocalZone
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.database.openDesktopDatabase
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID

/** 宿主范围的依赖容器；页面草稿由导航条目的 ViewModel 管理。 */
class DesktopAppContainer : ViewModel() {
    private val database = openDesktopDatabase()
    val localDataSources = LocalDataSources(database)
    val graph = AppGraph(localDataSources)
    val scheduling = LocalSchedulingGraph(
        sources = localDataSources,
        ownerUserId = LOCAL_ACTOR_ID,
        defaultZone = LocalZone.of("Asia/Shanghai"),
        localRevision = graph.localRevision,
    )

    override fun onCleared() {
        database.close()
    }
}
