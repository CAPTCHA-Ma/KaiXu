# 工程文档目录

| 目录 | 内容与常用格式 |
| --- | --- |
| `00-charter/` | 项目章程、人员职责、过程模型与风险登记，`.md` |
| `01-requirements/` | SRS版本、访谈纪要、变更单，`.md`、`.docx`；追踪矩阵`.csv` |
| `02-analysis/` | 用例、CRC、类图、活动图、状态图；保存可编辑图源与导出图 |
| `03-design/` | 当前MVVM说明；后续概要、接口、数据、部署和构件设计 |
| `04-testing/` | 当前验证记录；后续测试计划、报告与缺陷证据 |
| `05-iterations/` | 每轮计划、演示评审和回顾，`.md` |
| `06-reviews/` | 正式评审问题、修订、结论与PR链接，`.md` |
| `07-delivery/` | 安装、运行、发布范围、备份恢复和答辩演示 |
| `08-ai-and-contributions/` | AI工具用途和人审记录、真实贡献与互评 |

目录建立不代表完成真人访谈、评审或验收。过程记录必须反映实际活动。


客户端开发首先阅读 [分层与功能开发指南](03-design/client-mvvm-guide.md)，按职责查各层 README，再沿 [新增任务示例入口](03-design/mvvm-task-demo.md) 阅读源码。本次目录迁移与构建检查见 [验证记录](04-testing/client-layer-refactor-verification.md)。

导航已实际接入，新增页面和生命周期管理请阅读 [导航设计](03-design/client-navigation.md)，验证范围见 [导航验证记录](04-testing/client-navigation-verification.md)。

双端页面与导航的最新拆分见 [分层指南第 11 节](03-design/client-mvvm-guide.md#11-双端目录拆分与新文件归属)，结果见 [双端拆分验证](04-testing/client-platform-split-verification.md)。较早的分层和共享导航验证保留为历史记录。
