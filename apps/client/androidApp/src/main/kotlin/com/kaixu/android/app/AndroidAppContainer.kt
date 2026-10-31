package com.kaixu.android.app

import androidx.lifecycle.ViewModel
import com.kaixu.app.di.AppGraph

/** 宿主范围的依赖容器；页面草稿由导航条目的 ViewModel 管理。 */
class AndroidAppContainer : ViewModel() {
    val graph = AppGraph()
}
