# 共享源码导航

这里按职责分层，再按 tasks、workspaces 等业务分包。适宜放纯 Kotlin 类、接口、data class、enum class 和扩展函数；当前没有 Compose 页面或导航代码。

| 目录 | 适宜存放的文件 | 当前阅读入口 |
| --- | --- | --- |
| `presentation/` | 两端交互一致时复用的 ViewModel、UiState | TaskEditorViewModel、TaskEditorUiState |
| `domain/` | 业务对象、纯校验、仓储接口和 UseCase | Task、TaskTitle、CreateTaskUseCase |
| `data/` | 仓储实现、数据源、未来的 DTO 和转换 | DefaultTaskRepository、RoomTaskDataSource |
| `core/` | 多业务使用的网络、会话和平台能力接口 | 当前只有职责说明 |
| `app/di/` | 创建和连接公共依赖 | AppGraph |

调用链是“各端 Screen / Route → ViewModel → UseCase → Repository → DataSource”。UI 的实现在 androidApp 和 desktopApp；domain 不导入 data、界面或平台 API，data 不修改页面状态。

新增工作区功能时，在 domain/model/workspaces、domain/usecase/workspaces 等对应层新增包；各端页面放各端 presentation/ui/workspaces。不要同时维护一套重复的 features/workspaces/data 分层。

只有 README 的目录是预留位置。包目录不会自动形成编译隔离，仍需遵守层间依赖约定。详见 [开发指南](../../../../../../../../docs/03-design/client-mvvm-guide.md)。
