# Desktop 专属页面事件处理 预留

适宜放只服务本端交互流程的 DesktopTaskDetailsViewModel.kt，按 tasks、scheduling 等业务进一步分包。当前没有专属实现：标题录入仍使用 shared/presentation 下的 TaskEditorViewModel 和 TaskEditorUiState。

ViewModel 通过构造参数接收共享 UseCase，处理本端事件、草稿、提交与失败恢复，发布只读 StateFlow。业务校验与仓储实现继续复用，不复制到本目录。

两端布局不同而流程相同时继续使用共享实现；字段、事件、提交步骤明显不同时再增加本端实现。不要为形成对称目录提前创建空类，也不要让共享类依赖本端包。
