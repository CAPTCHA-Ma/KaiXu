# KaiXu 客户端变更记录

本记录从仓库可核实的 Room 基线开始，按变更编号从新到旧排列。`V1`、`V2` 等是固定条目编号，Room `v1`、`v2` 等是数据库结构版本。代码变更以对应 PR 或提交为准。

客户端行为、依赖版本或数据库结构发生变化时，在同一个 PR 更新本文件。每条写明日期、涉及范围、改动及原因、升级影响和已完成的验证，并链接到 PR、提交或验收记录。编号只递增，已有编号不改号、不复用。回退或替换时追加条目并引用原编号。数据库升级须同时提交新结构快照、迁移及旧库升级验证。

**下一条编号为 V9。**

- **V8 · 2026-11-08 · 本地资料与原记录编辑（Room v3→v4）**。工作区新增链接和文本资料、真实数量、搜索和同 ID 详情。任务及单次固定事件从工作区和全局入口编辑同一条记录，固定事件有重叠确认。为本地资料持久化，Room 自动迁移新增 `resources` 表及索引，旧四张表逐表对比一致。当前没有 v4→v3 降级迁移，回退应用时应使用升级前备份。Windows 上用隔离旧库完成升级、界面保存和重启验收，本地 66 项测试及两项 CI 通过。[PR #17](https://github.com/CAPTCHA-Ma/KaiXu/pull/17) · [提交 efadba5](https://github.com/CAPTCHA-Ma/KaiXu/commit/efadba54d0077eea7f4c45cbb0a023488608972b) · [验收记录](docs/04-testing/workspace-overview-verification.md) · [push CI](https://github.com/CAPTCHA-Ma/KaiXu/actions/runs/42448917819) · [PR CI](https://github.com/CAPTCHA-Ma/KaiXu/actions/runs/42448920734)
- **V7 · 2026-11-08 · 工作区概览同步长说明修复（Room v3）**。将 V6 纳入 #17，确保启用任务日程聚合后仍可滚动读完整说明并打开设置。Windows 用隔离库验证 40 行说明、任务和日程详情返回，本地 48 项测试及两项 CI 通过。[PR #17](https://github.com/CAPTCHA-Ma/KaiXu/pull/17) · [提交 c9d84e6](https://github.com/CAPTCHA-Ma/KaiXu/commit/c9d84e6c87f23e6596354a025a041ccae8258753)
- **V6 · 2026-11-08 · 长说明遮挡设置修复（Room v3）**。完整说明移入概览滚动区，顶部设置标签保持可见，避免既有长说明导致编辑入口不可达。用原 40 行说明实测，新增回归测试，本地 37 项及两项 CI 通过。[PR #16](https://github.com/CAPTCHA-Ma/KaiXu/pull/16) · [提交 49ac1c5](https://github.com/CAPTCHA-Ma/KaiXu/commit/49ac1c54258344fbfde1dd53b68c85065a187757)
- **V5 · 2026-11-08 · 桌面工作区概览（Room v3）**。从本地读取工作区直属任务、固定事件和本人工作时段，提供筛选、原 ID 只读详情及返回位置恢复。让全局任务和工作区使用同一条本地记录。本地 47 项测试及两项 CI 通过。[PR #17](https://github.com/CAPTCHA-Ma/KaiXu/pull/17) · [提交 b5be336](https://github.com/CAPTCHA-Ma/KaiXu/commit/b5be3362b5c9532257a09e9632550060f65fbbc4)
- **V4 · 2026-11-08 · 桌面工作区管理（Room v3）**。增加课程与个人项目统一目录、编辑、归档恢复及课程关联维护，使工作区数据可从桌面界面管理。Windows 退出重启后核对记录、归档及关联保留。[PR #16](https://github.com/CAPTCHA-Ma/KaiXu/pull/16) · [验收记录](docs/04-testing/workspace-management-verification.md)
- **V3 · 2026-11-05 · 本地工作区数据（Room v2→v3）**。Room 自动迁移新增 `workspaces` 表、课程自关联外键及同类型名称唯一索引，为工作区目录和项目归属提供持久化。原任务、事件和个人工作时段表保留，旧归属引用维持原值。旧库迁移及归档规则由 `WorkspaceDatabaseTest` 验证。[PR #12](https://github.com/CAPTCHA-Ma/KaiXu/pull/12)
- **V2 · 2026-11-04 · 任务和日程数据（Room v1→v2）**。手写 `Migration1To2` 保留任务标题，整数 ID 换成 UUID，并为旧行补入默认字段和迁移时的时间值。新增固定事件和个人工作时段表，为本地任务和日程建立统一数据层。旧文件升级由 `ScheduleDatabaseTest` 验证。[PR #7](https://github.com/CAPTCHA-Ma/KaiXu/pull/7) · [迁移代码](apps/client/shared/src/commonMain/kotlin/com/kaixu/data/datasource/local/database/migrations/Migration1To2.kt)
- **V1 · 2026-11-04 · Room v1 基线**。与 v2 同次提交的兼容快照只有整数主键和标题的任务表，用作后续迁移和旧库测试的起点。[PR #7](https://github.com/CAPTCHA-Ma/KaiXu/pull/7) · [v1 结构快照](apps/client/shared/schemas/com.kaixu.data.datasource.local.database.KaiXuDatabase/1.json)
