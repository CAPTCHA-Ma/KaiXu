# 新增任务示例阅读入口

示例已经迁移到按层组织的新目录，完整解释统一维护在 [客户端分层与功能开发指南](client-mvvm-guide.md)。第6节逐步追踪当前可运行代码，第7节说明后端接入，第8节说明如何将 SRS 业务分给各层。

页面以桌面端为例；Android 在自己的 presentation/ui/tasks 维护独立 Screen 与 Route，下面的共享业务链相同。

## 实际源码顺序

1. [TaskEditorScreen](../../apps/client/desktopApp/src/main/kotlin/com/kaixu/desktop/presentation/ui/tasks/TaskEditorScreen.kt)：画表单与任务列表。
2. [TaskEditorRoute](../../apps/client/desktopApp/src/main/kotlin/com/kaixu/desktop/presentation/ui/tasks/TaskEditorRoute.kt)：订阅状态，绑定事件。
3. [TaskEditorUiState](../../apps/client/shared/src/commonMain/kotlin/com/kaixu/presentation/state/tasks/TaskEditorUiState.kt)：页面草稿、加载和消息。
4. [TaskEditorViewModel](../../apps/client/shared/src/commonMain/kotlin/com/kaixu/presentation/viewmodel/tasks/TaskEditorViewModel.kt)：校验提示、保存状态、调用 UseCase。
5. [CreateTaskUseCase](../../apps/client/shared/src/commonMain/kotlin/com/kaixu/domain/usecase/tasks/CreateTaskUseCase.kt)：业务校验与标题规范化。
6. [TaskRepository](../../apps/client/shared/src/commonMain/kotlin/com/kaixu/domain/repository/TaskRepository.kt)：业务数据能力接口。
7. [DefaultTaskRepository](../../apps/client/shared/src/commonMain/kotlin/com/kaixu/data/repository/DefaultTaskRepository.kt)：调用数据源，成功后发布列表。
8. [InMemoryTaskDataSource](../../apps/client/shared/src/commonMain/kotlin/com/kaixu/data/datasource/memory/InMemoryTaskDataSource.kt)：真正执行演示读写。
9. [AppGraph](../../apps/client/shared/src/commonMain/kotlin/com/kaixu/app/di/AppGraph.kt)：创建并连接依赖。

示例只展示 FR-T01 的部分手工创建行为，不含正式后端、账号、截止、估时、权限、持久化或同步。测试与构建范围见 [双端拆分验证记录](../04-testing/client-platform-split-verification.md)。
