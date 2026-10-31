# Android 应用装配与生命周期

`AndroidApp.kt` 是本端 Compose 根界面：取得宿主范围的 AndroidAppContainer，应用本端主题，再调用 AndroidNavigation。`AndroidAppContainer.kt` 继承 Lifecycle ViewModel，持有一份共享 AppGraph。

适宜存放根界面、宿主依赖容器，以及将来本端对公共依赖图的装配接线。这里允许知道具体依赖实现来完成装配，不在这里写任务校验、HTTP 端点或数据库查询。

仓储和 UseCase 随宿主容器复用；页面 ViewModel 随导航条目创建。不要在 Screen 的重绘过程中直接 new AppGraph，也不要把所有页面草稿放进宿主容器。

当前 AppGraph 使用进程内数据，容器并不提供跨端同步、登录会话或进程终止后的恢复。以后换账号时需要显式清理旧账号依赖和未完成请求。
