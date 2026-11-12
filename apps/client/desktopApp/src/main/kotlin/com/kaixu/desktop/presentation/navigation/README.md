# Desktop 导航与页面装配

`DesktopDestination.kt` 用带类型的目的地统一定义每个页面的路由和 `title`，包括主页面、设置及带 ID 的任务、事件、工作时段、资源详情。`DesktopNavigation.kt` 维护本端 NavHost 和页面装配；`DesktopNavigator.kt` 维护窗口访问历史和跳转。页面名称只在目的地定义一处，当前标题、悬停预览、侧栏标签及前进后退共用该定义。

本层接收公共 AppGraph，在任务 NavBackStackEntry 范围取得 TaskEditorViewModel，并注入三个 UseCase。仓储由宿主容器共享，草稿由页面 ViewModel 保留；Screen 与普通 Route 不访问数据源。

调用 `navigator.open(DesktopDestination.Settings)` 或 `navigator.open(DesktopDestination.TaskRecord(id))` 等带类型的目标进行跳转；导航历史保存该对象及所属主栏目。设置页往返时任务条目仍在栈中，所以草稿与失败提示仍在；条目真正出栈后清理 ViewModel。桌面已保存任务通过 Room 持久化，进程重启后仍在；未保存草稿不持久化。

需要悬停预览的导航按钮通过 `DesktopNavigationButton` 指定 `destination`，并传入导航层提供的 `onNavigate` 和 `onPreview`。预览回调的参数是 `(source: String, destination: DesktopDestination?)`，`null` 表示退出或清除；不传名称字符串。`source` 为控件唯一标识，防止旧控件的退出事件清掉新预览。页面内部尚未适配悬停的按钮保留现有行为。

新增页面时依次添加目的地（声明路由和唯一维护的页面名称）、NavHost 登记、必要的页面 ViewModel 和 Route 回调。参数优先传 taskId 等稳定标识，不传整个 UiState、DTO 或 ViewModel。修改页面名称只改目的地的 `title`；不要为新页面另建名称映射或为按钮提供预览文案。这里不实现业务规则。
