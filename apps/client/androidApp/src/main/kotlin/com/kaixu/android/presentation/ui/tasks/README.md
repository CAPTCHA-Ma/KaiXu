# Android 任务页面

`TaskEditorScreen.kt` 绘制标题输入、保存、进度、消息和列表，仅消费 TaskEditorUiState 与回调。`TaskEditorRoute.kt` 使用 collectAsStateWithLifecycle 订阅共享 TaskEditorViewModel，连接设置跳转。

适宜存放本端任务列表、详情、快捷新增或编辑页面，以及任务页面专属小组件。当前只有标题录入示例；截止、估时、优先级等完整字段尚未实现。

修改布局和控件改 Screen；修改订阅与事件绑定改 Route；公共保存规则改 shared/domain。两端 Screen 是各自可编辑的实现，当前 ViewModel 与状态类型仍共享。

页面 ViewModel 由 AndroidNavigation 在 task-editor 导航条目内取得，Route 不持有 NavController。页面使用本地数据库，不再提供模拟失败或取消保存入口。
