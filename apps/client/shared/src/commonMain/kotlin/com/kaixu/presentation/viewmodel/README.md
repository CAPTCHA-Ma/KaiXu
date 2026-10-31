# ViewModel

按业务写 `XxxViewModel.kt`，通过构造参数接收 UseCase。公开只读 UiState 和事件方法，如 `onTitleChanged()`、`onSaveClicked()`。

事件处理顺序：读取输入 → 本地提示校验 → 设置进行中状态 → 在 `viewModelScope` 调用业务动作 → 按成功或失败更新状态。协程取消须重新抛出，不能当普通失败。

这里管理页面交互、重复点击、错误反馈和草稿；可复用的业务规则放 domain。不要导入 Ktor、数据库驱动、Repository 实现类或平台窗口。

## 共享条件与文件归属

这里只有两端流程相同的 ViewModel。桌面批量编辑、Android 快捷操作等不同流程分别放各端 presentation/viewmodel，共同复用 UseCase；不要通过复制 TaskTitle 等规则来实现差异。
