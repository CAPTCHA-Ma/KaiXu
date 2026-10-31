# 公共依赖装配区域

这里保留共享业务依赖的装配，不再存放根界面 App.kt 或宿主 ViewModel。当前只有 `di/AppGraph.kt`，用于连接内存数据源、任务仓储和 UseCase。

适宜存放公共依赖配置、共享业务图的工厂，以及将来按账号创建数据范围的装配代码。具体实现放 di；业务判断应回到 domain，网络访问应回到 data。

AndroidApp、AndroidAppContainer 位于 androidApp 的 app 包；DesktopApp、DesktopAppContainer 位于 desktopApp 的 app 包。容器当前继承 ViewModel，并由各端宿主管理生命周期；页面 ViewModel 由各端导航条目管理。

应用范围内复用同一个仓储，避免页面重绘时重建数据源。当前容器不是已实现的登录会话，也不提供应用重启恢复。
