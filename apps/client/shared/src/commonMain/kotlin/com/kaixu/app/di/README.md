# 公共依赖装配

`AppGraph.kt` 显式接收各本地数据源接口，手工装配 `Repository` 实现与领域 `UseCases` 门面。它向桌面与 Android 两端暴露一致的业务能力，不创建页面、导航控制器或页面 ViewModel，也不决定布局。当前无需 DI 框架。

四类核心业务统一收口到同一个 `AppGraph` 实例中，共享同一个 `LocalTransaction`（`sources.transaction`）与同一个本地变更修订信号 `localRevision: LocalDataRevision`：
- 任务模块：`taskRepository` → `taskUseCases: TaskUseCases`
- 固定日程与日/周平铺日历查询：`fixedEventRepository` → `fixedEventUseCases: FixedEventUseCases`
- 个人工作时段模块：`workSessionRepository` → `workSessionUseCases: WorkSessionUseCases`
- 专注计时模块：`focusRepository` → `focusUseCases: FocusUseCases`

页面与 ViewModel 统一注入 `graph.taskUseCases`、`graph.fixedEventUseCases`、`graph.workSessionUseCases` 与 `graph.focusUseCases`。任一模块在写事务成功提交后递增共享 `localRevision`，下游观察流（如 `queryCalendar.observe`、`queryTasks.observe`、`queryByTask.observe`、`getDetail.observe`）即可自动重算最新快照。

`AndroidAppContainer` 与 `DesktopAppContainer` 分别打开本端 Room 数据库，创建 `LocalDataSources` 并直接构造 `AppGraph(localDataSources)`（自动注入 `tasks`、`fixedEvents`、`workspaces`、`workSessions`、`focus` 与 `transaction`）；宿主清理时关闭数据库。没有默认内存实现或模拟失败开关；主构造保留可选数据源参数以便单模块测试兼容，未注入对应数据源时调用其读写会立即抛出明确错误。远程同步尚未实现。

同一个宿主容器持有一份 `AppGraph`，图中同一个仓储供页面复用。`AndroidAppContainer` 与 `DesktopAppContainer` 分别创建各自实例，两端不会共享同一内存对象。页面 ViewModel 的创建和清理由各端 navigation 管理。

`LocalSchedulingGraph` 作为排期薄兼容层保留，基于 `LocalZone` 与传入的 `LocalDataRevision` 委托至新版 `DefaultWorkSessionRepository`、`DefaultFocusRepository`、`WorkSessionUseCases` 与 `FocusUseCases`。
