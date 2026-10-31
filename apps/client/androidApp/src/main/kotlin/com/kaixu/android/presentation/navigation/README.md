# Android 导航与页面装配

`AndroidDestination.kt` 定义 task-editor、settings 目的地；`AndroidNavigation.kt` 维护本端 NavHost、返回栈和跳转回调。未来的本端导航参数、路由标识与页面装配也放这里。

本层接收公共 AppGraph，在任务 NavBackStackEntry 范围取得 TaskEditorViewModel，并注入三个 UseCase。仓储由宿主容器共享，草稿由页面 ViewModel 保留；Screen 与普通 Route 不访问数据源。

打开设置使用 navigate 与 launchSingleTop，返回使用 popBackStack。设置页往返时任务条目仍在栈中，所以草稿与失败提示仍在；条目真正出栈后清理 ViewModel，进程重启仍会丢失内存数据。

Android 系统返回由导航框架处理；若以后接底部导航，需要明确目的地与状态恢复策略。

新增页面时依次添加目的地、NavHost 登记、必要的页面 ViewModel 和 Route 回调。参数优先传 taskId 等稳定标识，不传整个 UiState、DTO 或 ViewModel。这里不实现业务规则。
