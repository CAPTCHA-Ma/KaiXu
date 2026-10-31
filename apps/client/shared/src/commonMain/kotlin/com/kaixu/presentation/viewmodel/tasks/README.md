# 任务 ViewModel

`TaskEditorViewModel.kt` 注入 CreateTaskUseCase、ObserveTasksUseCase、RefreshTasksUseCase。初始化时订阅任务并刷新；输入改变只改页面草稿；保存成功后清空草稿。

保存前立即设置 `isSaving` 防止连续点击。TaskTitle 提供即时校验；CreateTaskUseCase 也执行同一规则，保证从其他入口调用仍然有效。这里不重复实现规则。

内存演示支持取消写入前的等待。未来 HTTP 取消只代表客户端停止等待，服务端可能已提交；确认结果需依赖服务端幂等与查询。

## 当前使用者

AndroidNavigation 与 DesktopNavigation 分别在自己的任务条目创建此类，传入各自 AppGraph 的三个 UseCase。两端共享源码，但不会共享实例、草稿、列表或运行中的协程。
