# Android 专属页面状态 预留

适宜放只服务本端交互流程的 AndroidTodayUiState.kt，按 tasks、scheduling 等业务进一步分包。当前没有专属实现：标题录入仍使用 shared/presentation 下的 TaskEditorViewModel 和 TaskEditorUiState。

UiState 使用不可变 data class，表示本端草稿、选中项、加载与错误；不作为 HTTP DTO 或数据库 Entity。纯局部焦点和滚动可留在 Composable 中。

两端布局不同而流程相同时继续使用共享实现；字段、事件、提交步骤明显不同时再增加本端实现。不要为形成对称目录提前创建空类，也不要让共享类依赖本端包。
