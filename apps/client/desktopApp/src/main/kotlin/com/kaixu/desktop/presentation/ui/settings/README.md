# Desktop 设置与说明页面

`SettingsScreen.kt` 显示当前演示范围，并通过 onBack 回调请求返回。它是纯显示页，没有设置保存能力，因此不额外建立空 ViewModel。

适宜存放本端设置页、说明区及其专用小组件。未来有真实设置时，表单状态放本端 state，事件处理放本端 viewmodel；通用偏好或会话业务按复用情况进入 shared。

返回由 DesktopNavigation 执行 popBackStack，避免再次 navigate 到任务页而创建新的编辑条目。不要在说明页提供尚未接通的云同步或账号开关。
