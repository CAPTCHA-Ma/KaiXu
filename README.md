# KaiXu 开序项目

这是 Windows 与 Android 双端应用的工程目录。客户端使用 Kotlin Multiplatform、Compose Multiplatform 与 MVVM，并增加清晰的业务动作和数据访问分层。

**第一次阅读先看 [客户端分层与功能开发指南](docs/03-design/client-mvvm-guide.md)**，其中包含后端分层类比、真实新增任务调用链、状态与 DTO 区别，以及 SRS 功能拆分方法。运行工程看 [客户端 README](apps/client/README.md)。

软件系统设计（架构、数据库、接口、UI/UX、安全）见 [设计文档索引](docs/03-design/README.md)；可直接打开[高保真原型](docs/03-design/system-design/prototype/index.html)查看双端界面。

## 项目目录

| 目录 | 职责 |
| --- | --- |
| `apps/client/` | 双端 Gradle 工程，IDE 打开此处 |
| `apps/client/shared/` | 共享业务、数据能力、可复用 ViewModel 与状态 |
| `apps/client/androidApp/` | Android 入口、独立页面与导航、权限与安装配置 |
| `apps/client/desktopApp/` | 桌面窗口、独立页面与导航、入口与安装包配置 |
| `services/api/` | FastAPI 后端的预留结构，尚无可运行服务 |
| `contracts/` | 客户端与后端共同确认的接口、规则与响应样本 |
| `docs/` | 需求、设计、测试、迭代和交付记录 |
| `tests/acceptance/` | 用户视角验收步骤 |
| `infra/`、`scripts/` | 部署与辅助工具的开发位置 |
| `.github/` | 已有 CI 配置与协作模板 |

## 客户端主链条

```text
UI → ViewModel → UseCase → Repository 接口
                              ↓ 注入的实现执行
                         Repository 实现 → DataSource → 后端 API
                              ↓
                         已确认的数据 → UiState → UI 更新
```

当前 DataSource 是内存演示；后端 API 尚未接入。客户端 Model 范围包括业务对象、规则、仓储与数据源，不只是数据类。

页面与导航分别位于各端模块；下表其余公共层的共享源码根目录为 `apps/client/shared/src/commonMain/kotlin/com/kaixu/`：

| 层 | 目录 | 从这里开始阅读 |
| --- | --- | --- |
| Android 界面 | [androidApp/presentation](apps/client/androidApp/src/main/kotlin/com/kaixu/android/presentation/README.md) | AndroidNavigation、任务 Screen / Route |
| 桌面界面 | [desktopApp/presentation](apps/client/desktopApp/src/main/kotlin/com/kaixu/desktop/presentation/README.md) | DesktopNavigation、任务 Screen / Route |
| 状态 | [presentation/state](apps/client/shared/src/commonMain/kotlin/com/kaixu/presentation/state/README.md) | TaskEditorUiState |
| 页面行为 | [presentation/viewmodel](apps/client/shared/src/commonMain/kotlin/com/kaixu/presentation/viewmodel/README.md) | TaskEditorViewModel |
| 业务动作 | [domain/usecase](apps/client/shared/src/commonMain/kotlin/com/kaixu/domain/usecase/README.md) | CreateTaskUseCase |
| 业务模型与规则 | [domain](apps/client/shared/src/commonMain/kotlin/com/kaixu/domain/README.md) | Task、TaskTitle |
| 仓储契约 | [domain/repository](apps/client/shared/src/commonMain/kotlin/com/kaixu/domain/repository/README.md) | TaskRepository |
| 数据访问 | [data](apps/client/shared/src/commonMain/kotlin/com/kaixu/data/README.md) | DefaultTaskRepository、InMemoryTaskDataSource |
| 对象接线 | [app/di](apps/client/shared/src/commonMain/kotlin/com/kaixu/app/di/README.md) | AppGraph |

## 当前交付范围

已实现可运行的标题录入、任务列表、保存状态、模拟失败、保留草稿、重试与取消示例。没有完整任务字段、账号、持久化、真实网络、双端同步或离线缓存；只有 README 的目录是后续位置。

最新重构检查见 [双端拆分验证](docs/04-testing/client-platform-split-verification.md)，较早的分层迁移见 [历史记录](docs/04-testing/client-layer-refactor-verification.md)，原始示例操作见 [验收步骤](tests/acceptance/new-task-demo.md)。

已加入任务页与设置说明页之间的导航。AndroidNavigation 与 DesktopNavigation 分别为任务导航条目取得页面 ViewModel，返回时保留草稿；详见 [导航接入设计](docs/03-design/client-navigation.md)。

双端根界面、导航和页面已拆分，各职责目录 README 说明适宜存放的文件、协作边界与当前实现状态。结构与命令见 [客户端总览](apps/client/README.md)，结果见 [双端拆分验证](docs/04-testing/client-platform-split-verification.md)。
