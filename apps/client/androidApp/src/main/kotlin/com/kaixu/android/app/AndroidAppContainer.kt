package com.kaixu.android.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.kaixu.app.di.AppGraph
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.database.openAndroidDatabase

/** 宿主范围的依赖容器；页面草稿由导航条目的 ViewModel 管理。 */
class AndroidAppContainer(application: Application) : AndroidViewModel(application) {
    private val database = openAndroidDatabase(application)
    val localDataSources = LocalDataSources(database)
    val graph = AppGraph(
        source = localDataSources.tasks,
        fixedEventSource = localDataSources.fixedEvents,
    )

    override fun onCleared() {
        database.close()
    }
}
