# 可复用的表现层逻辑

这里只存放适用于两端相同交互流程的 ViewModel 和 UiState。当前任务标题录入的草稿、加载、保存、失败与重试流程一致，因此 TaskEditorViewModel 和 TaskEditorUiState 继续复用。

- `viewmodel/`：事件处理、协程、业务调用和只读状态发布，例如 TaskEditorViewModel.kt。
- `state/`：不可变的草稿、错误、保存状态等，例如 TaskEditorUiState.kt。

完整 Screen、Route、components、theme 和 navigation 已移到两个应用模块。这里不写 @Composable 函数、不持有 NavController、Activity、Window，也不存屏幕宽度、侧栏展开或滚动位置等本端视觉状态。

只改变布局可继续复用 ViewModel；若桌面完整编辑与 Android 快捷录入的字段、事件、流程不同，则分别在各端 presentation/viewmodel 和 state 实现，共同调用 shared/domain/usecase。避免给共享 ViewModel 添加大量 isAndroid、isDesktop 分支。

共享的是代码，实际 ViewModel 由各端导航创建、各自持有状态。ViewModel 不是业务规则的唯一归属；跨页面和跨端的规则仍属于 domain。
