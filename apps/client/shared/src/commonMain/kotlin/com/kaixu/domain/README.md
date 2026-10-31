# 业务层

这里表达“客户端要做什么”，属于 MVVM 的 Model 范围。目录名采用 domain，但只做本项目需要的轻量分层。

| 目录 | 写什么 |
| --- | --- |
| `model/` | 业务对象，例如 Task、未来的 Workspace、WorkSession |
| `validation/` | 可复用的标题、日期区间等纯规则 |
| `repository/` | 业务所需的数据能力接口 |
| `usecase/` | 一次业务动作，例如创建任务、完成任务 |

只依赖 Kotlin、协程等通用能力；不依赖 Compose、HTTP JSON、数据库、Android 或 data 实现。Repository 接口放这里是本项目采用的依赖倒置约定，并非 MVVM 唯一规定。

服务端负责最终权限、版本、幂等和事务；UseCase 的检查用于交互与业务编排，不等于后端已经校验。

## 业务领域目录

新增业务时按 model、validation、repository、usecase 分别放文件，再用 tasks、workspaces、scheduling 等子包区分业务。UI 是否提供某个入口由各端决定，不在这里用屏幕宽度决定业务行为。
