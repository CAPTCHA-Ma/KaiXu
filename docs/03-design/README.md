# 设计文档索引

本目录保存开序（KaiXu）在实现前的设计依据。需求基线为 2026-10-23 的《Software Requirements Specification》，端能力、离线、AI 与番茄钟规则以 2026-11-01 的《KaiXu 用例分解》为准；两者冲突时以用例分解的修订规则为准，并在设计文档中明确记录差异。

## 系统设计

| 文档 | 内容 | 主要读者 |
| --- | --- | --- |
| [系统设计总览](system-design/README.md) | 设计目标、关键决策、范围、需求追溯、文档阅读顺序 | 全员、评审 |
| [架构设计](system-design/01-architecture-design.md) | 技术栈、模块化单体、客户端分层、同步架构、部署拓扑 | 后端、客户端、评审 |
| [数据库设计](system-design/02-database-design.md) | ER 图、表结构、隐私分区、索引、完整 DDL | 后端、测试 |
| [接口设计](system-design/03-api-design.md) | REST 规范、端点目录、数据格式、同步协议、错误码 | 前后端联调 |
| [UI/UX 设计](system-design/04-ui-ux-design.md) | 信息架构、线框图、视觉规范、交互状态、高保真原型 | 客户端、产品、评审 |
| [安全设计](system-design/05-security-design.md) | 认证授权、加密、隐私隔离、同步与 AI 安全、威胁模型 | 后端、评审、测试 |
| [数据库 DDL](system-design/schema.sql) | PostgreSQL 16 可执行建表脚本 | 后端 |
| [数据库冒烟测试](system-design/schema-smoke-test.sql) | 8 项关键约束验证脚本 | 后端、测试 |
| [OpenAPI 草案](system-design/openapi.yaml) | OpenAPI 3.1 核心链路接口契约草案 | 前后端联调 |
| [高保真原型](system-design/prototype/index.html) | 可浏览器打开的静态高保真界面 | 产品、客户端、评审 |

## 已有客户端设计

- [客户端分层与功能开发指南](client-mvvm-guide.md)：KMP + Compose + MVVM 的代码组织方式。
- [客户端导航设计](client-navigation.md)：任务页与设置页导航、草稿保留。
- [MVVM 任务示例](mvvm-task-demo.md)：从界面到数据源的完整调用链。

## 阅读顺序

1. 先读[系统设计总览](system-design/README.md)，确认范围、技术选型与需求差异。
2. 后端从[架构设计](system-design/01-architecture-design.md)进入，再读[数据库设计](system-design/02-database-design.md)与[接口设计](system-design/03-api-design.md)。
3. 客户端从[UI/UX 设计](system-design/04-ui-ux-design.md)进入，结合已有的[客户端分层指南](client-mvvm-guide.md)。
4. 评审与测试重点阅读[安全设计](system-design/05-security-design.md)和各文档末尾的验收映射。

## 文档状态

- 版本：v1.0
- 基线日期：2026-11-02
- 状态：设计基线，可进入迭代拆解；接口与 DDL 在首次联调评审后冻结 v1.1。
- 约定：文档中的 `FR-*`、`UC*`、`BR*`、`NFR-*`、`AT*` 均引用需求文档编号；新增设计编号使用 `ADR-*`、`API-*`、`TBL-*`。
