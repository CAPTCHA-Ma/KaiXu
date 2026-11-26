# 公共依赖装配

`AppGraph.kt` 显式接收 TaskDataSource，手工创建 Repository 实现 → UseCase。它向两端暴露相同业务能力，不创建页面、导航控制器或页面 ViewModel，也不决定布局。当前无需 DI 框架。

任务动作集中到 `taskUseCases: TaskUseCases`，复用图中的同一个 `TaskRepository`；固定日程与日/周平铺日历查询集中到 `fixedEventUseCases: FixedEventUseCases`，复用图中的同一个 `FixedEventRepository`。
页面与 ViewModel 注入 `graph.taskUseCases` 与 `graph.fixedEventUseCases`。

适宜存放 AppGraph.kt、未来的公共依赖工厂和账号范围依赖配置。若有平台差异，优先通过构造参数接收公共接口，由各端或平台源集提供实现；不要在这里导入 Activity 或 Window。

AndroidAppContainer 与 DesktopAppContainer 分别打开本端 Room 数据库，创建 LocalDataSources 并直接构造 `AppGraph(localDataSources)`；图内为任务与固定日程模块一并装配 `tasks`、`fixedEvents`、`workspaces`、`workSessions`、`LocalTransaction`（`sources.transaction`）与共享修订信号 `LocalDataRevision`；宿主清理时关闭数据库。没有默认内存实现或模拟失败开关。远程同步尚未实现。

同一个宿主容器持有一份 AppGraph，图中同一个仓储供页面使用。AndroidAppContainer、DesktopAppContainer 分别创建各自实例，两端不会共享同一内存对象。

页面 ViewModel 的创建和清理由各端 navigation 管理。退出账号时清理图、取消旧请求、隔离缓存是后续会话工作，不在普通页面重绘时执行。

`LocalSchedulingGraph` 接收同一 `LocalDataSources`、本人身份和所选时区的日期转换函数，
装配计划时段仓储、实际计时仓储及其 UseCase，并注入共享的本地写事务实现。
桌面宿主通过 `scheduling` 暴露该图。Android 的远程计时入口尚未接入，不装配本地排期写入图。
