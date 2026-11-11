# 任务 ViewModel

`TaskEditorViewModel.kt` 注入一个 TaskUseCases，通过 observe、refresh 和 create 方法订阅、刷新和创建任务。初始化时订阅任务并刷新；输入改变只改页面草稿；保存成功后清空草稿。

保存前立即设置 `isSaving` 防止连续点击。TaskTitle 提供即时校验；TaskUseCases.create 也执行同一规则，保证从其他入口调用仍然有效。这里不重复实现规则。

页面不再提供演示取消按钮。协程取消会结束等待，但不能保证撤回已提交的数据库写入；测试只验证提交前取消的行为。

## 当前使用者

AndroidNavigation 与 DesktopNavigation 分别在自己的任务条目创建此类，传入各自 AppGraph.taskUseCases。两端共享源码，但不会共享实例、草稿、列表或运行中的协程。
