# 开序双端导航与生命周期

Android 与桌面分别维护导航图，当前均使用 Navigation Compose 2.9.1，保留任务录入、设置说明两个目的地。SRS FR-X02 和表 13 允许两端拥有不同页面与操作范围，因此不再使用共享 App 或共享导航图统一页面结构。

## 源码入口

| 职责 | Android | 桌面 |
| --- | --- | --- |
| 根界面 | [AndroidApp](../../apps/client/androidApp/src/main/kotlin/com/kaixu/android/app/AndroidApp.kt) | [DesktopApp](../../apps/client/desktopApp/src/main/kotlin/com/kaixu/desktop/app/DesktopApp.kt) |
| 宿主依赖容器 | [AndroidAppContainer](../../apps/client/androidApp/src/main/kotlin/com/kaixu/android/app/AndroidAppContainer.kt) | [DesktopAppContainer](../../apps/client/desktopApp/src/main/kotlin/com/kaixu/desktop/app/DesktopAppContainer.kt) |
| 页面导航 | [AndroidNavigation](../../apps/client/androidApp/src/main/kotlin/com/kaixu/android/presentation/navigation/AndroidNavigation.kt) | [DesktopNavigation](../../apps/client/desktopApp/src/main/kotlin/com/kaixu/desktop/presentation/navigation/DesktopNavigation.kt) |
| 任务连接 | [Android Route](../../apps/client/androidApp/src/main/kotlin/com/kaixu/android/presentation/ui/tasks/TaskEditorRoute.kt) | [桌面 Route](../../apps/client/desktopApp/src/main/kotlin/com/kaixu/desktop/presentation/ui/tasks/TaskEditorRoute.kt) |

MainActivity 调用 AndroidApp，main() 的窗口调用 DesktopApp。两端各自维护 Destination、Screen、SettingsScreen 与主题；共享 TaskEditorViewModel、TaskEditorUiState、UseCase 和数据访问。

## 依赖图与导航图

AppGraph 装配数据源、仓储和 UseCase；Navigation 组织页面、目的地与返回栈。各端 AppContainer 在宿主范围持有一份 AppGraph，导航在目的地范围创建页面 ViewModel。

```text
各端宿主 ViewModelStore
    → 本端 AppContainer
        → 本端持有的 AppGraph
            → 同一个仓储 → UseCase
                ↓ 注入
各端 Navigation 的 task-editor 条目
    → 自己的 TaskEditorViewModel → 自己的任务 Route / Screen
```

共享源码不使实例跨进程共享。当前两个图只访问各自内存数据源，关闭应用丢失数据，尚无云端同步。

## 页面作用域与往返

两端导航都在 composable 的 entry 范围取得页面 ViewModel：

```kotlin
val editor = viewModel<TaskEditorViewModel>(viewModelStoreOwner = entry) {
    TaskEditorViewModel(graph.createTask, graph.observeTasks, graph.refreshTasks)
}
```

entry 是 NavBackStackEntry，也是 ViewModelStoreOwner。同一条目重绘时复用已有 ViewModel；真正出栈时清理。不要在根 App 中提前创建全部页面 ViewModel，也不要在普通页面重绘时重新建立 AppGraph。

任务页打开设置后，编辑条目仍在返回栈。设置返回调用 popBackStack，回到原条目，所以草稿、失败提示与已确认列表仍在。不要以 navigate(task-editor) 替代返回，避免创建新的编辑条目。设置跳转使用 launchSingleTop 防止在栈顶重复添加。

Android 系统返回由导航框架处理；桌面当前使用页内返回按钮，没有新增返回快捷键。普通导航保留不等于进程终止后恢复。

## 新增和区分两端页面

在本端 Destination 添加标识，在本端 Navigation 登记页面、取得必要的 ViewModel，再连接本端 Route。Screen 使用状态和事件回调；NavController 不进入 UseCase。参数传 taskId 等稳定标识，不传整个 UiState、DTO 或 ViewModel。

仅布局不同可继续共享 ViewModel；字段、事件与流程明显不同时，在本端 presentation/viewmodel 和 state 实现，共同调用共享 UseCase。纯显示说明页无需空 ViewModel。

宿主容器当前只有演示依赖；真实登录、退出、账号缓存隔离与未完成请求清理仍需在会话接入时明确设计。

## 验证

桌面 [AppNavigationTest](../../apps/client/desktopApp/src/test/kotlin/com/kaixu/desktop/AppNavigationTest.kt) 使用真实 DesktopApp，覆盖未保存草稿、保存后列表、失败提示的设置往返和重试。共享逻辑仍由 shared:jvmTest 验证。

最新构建和测试结果见 [双端拆分验证](../04-testing/client-platform-split-verification.md)。Android 返回、旋转与 Windows 安装等设备行为需另行验收。
