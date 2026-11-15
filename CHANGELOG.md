# KaiXu 客户端变更记录

本记录从仓库可核实的 Room 基线开始，按变更编号从新到旧排列。`V1`、`V2` 等是固定条目编号，Room `v1`、`v2` 等是数据库结构版本。代码变更以对应 PR 或提交为准。

客户端行为、依赖版本或数据库结构发生变化时，在同一个 PR 更新本文件。每条写明日期、涉及范围、改动及原因、升级影响和已完成的验证，并链接到 PR、提交或验收记录。编号只递增，已有编号不改号、不复用。回退或替换时追加条目并引用原编号。数据库升级须同时提交新结构快照、迁移及旧库升级验证。

**下一条编号为 V13。**

- **V12 · 2026-11-14 · 首页卡片日程首版（Issue #24）**。左侧聚合任务截止、固定事件和本人工作时段，提供当前事项居中、昨天至明天的有限滚动、边界继续滚动进入日程页和原记录详情入口。卡片直接显示在白色背景上，区域宽度保持 62%，高度约占页面内容区的 80%，上下各预留约 10% 用于其他内容。滚轮每格切换一张卡片，采用中央最大、左侧对齐、右侧逐级缩进的轮式布局；缩放后卡片的边缘间距固定为 14 dp，不随窗口拉伸。中央及上下相邻三张卡片保持完全不透明，仅更外侧卡片渐淡。切换采用轻微欠阻尼弹簧，越过中央约 4 dp 后收回，右边缘同步轻微展开再恢复；连续输入沿用速度。悬停时卡片轻微放大并随指针相对位置柔和偏移，移出后平滑复位，滚动时清除悬停以避免干扰切换。横向绘制边界随卡片宽度和悬停幅度扩展，修复宽窗口下左移时卡面被裁切；上下继续裁剪并保留预留区域。清理旧横向偏移、透视倾斜、巡航与强制停止分支，以及日程摘要中未使用的说明字段。复用本地 DataSource、课次展开、原记录详情和统一导航；数据库结构与依赖不变。增加显式预览数据命令，普通启动不自动造数据。验证结果见 [设计与验证说明](docs/03-design/desktop-home-agenda.md)。

- **V11 · 2026-11-14 · 桌面首页入口与圆角内容面板（Issue #24）**。启动进入空白首页，标题栏新增首页按钮，设置、返回、前进右移；首页保留悬停反馈，不显示选中态，其中轴线与侧栏按钮对齐。所有桌面页面统一置于白色圆角面板，以轻微阴影和浅色边框表现悬浮层次，外部沿用标题栏、侧栏背景。首页接入窗口历史，任务、工作区和日程保存并恢复页面状态；非鼠标点击不再消耗下一次悬停预览。数据库与依赖版本不变。Windows / JBRSDK 21.0.9 下桌面编译及全部 25 项桌面测试通过，检查了首页、工作区与日程面板截图；对齐与阴影微调后桌面编译和 12 项导航、按钮测试通过，检查了更新后的首页截图。未重新执行原生窗口交互及安装包验收。见 [设计与验证说明](docs/03-design/desktop-titlebar.md)。

- **V10 · 2026-11-12 · Windows 自定义标题栏与页面历史**。新增固定高度灰白标题栏及设置、返回、前进按钮，接入 JBR API 1.5.0，保留并设色原生窗口控制按钮；现有主页面、设置与记录详情统一经过窗口访问历史。任务草稿、工作区筛选保留，后退后新跳转清除前进分支；数据库不变，不引入 Jewel。Linux 下完成编译，18 项桌面测试全部通过；Windows 原生窗口和安装包待验收，详见 [设计与验证说明](docs/03-design/desktop-titlebar.md)。

- **V9 · 2026-11-12 · 桌面 JBR 21 运行配置**。新增用户级 `jbrHome` 属性和 `JBR_HOME` 环境变量入口，用于指定桌面启动、桌面测试与打包的 JBR，增加 `desktopRuntimeInfo` 检查命令及本机配置说明。未配置时沿用原 Java 选择方式；不引入 Jewel、JBR API 或数据库升级。Linux 使用 JBR 21.0.10 完成运行时信息检查、桌面编译和现有 `ResourceUrlTest`；Windows 窗口及安装包待验证，见 [桌面配置说明](apps/client/desktopApp/README.md#使用-jbr-21不引入-jewel)。

- **V8 · 2026-11-08 · 本地资料与原记录编辑（Room v3→v4）**。工作区新增链接和文本资料、真实数量、搜索和同 ID 详情。任务及单次固定事件从工作区和全局入口编辑同一条记录，固定事件有重叠确认。为本地资料持久化，Room 自动迁移新增 `resources` 表及索引，旧四张表逐表对比一致。当前没有 v4→v3 降级迁移，回退应用时应使用升级前备份。Windows 上用隔离旧库完成升级、界面保存和重启验收，本地 66 项测试及两项 CI 通过。[PR #17](https://github.com/CAPTCHA-Ma/KaiXu/pull/17) · [提交 efadba5](https://github.com/CAPTCHA-Ma/KaiXu/commit/efadba54d0077eea7f4c45cbb0a023488608972b) · [验收记录](docs/04-testing/workspace-overview-verification.md) · [push CI](https://github.com/CAPTCHA-Ma/KaiXu/actions/runs/42448917819) · [PR CI](https://github.com/CAPTCHA-Ma/KaiXu/actions/runs/42448920734)
- **V7 · 2026-11-08 · 工作区概览同步长说明修复（Room v3）**。将 V6 纳入 #17，确保启用任务日程聚合后仍可滚动读完整说明并打开设置。Windows 用隔离库验证 40 行说明、任务和日程详情返回，本地 48 项测试及两项 CI 通过。[PR #17](https://github.com/CAPTCHA-Ma/KaiXu/pull/17) · [提交 c9d84e6](https://github.com/CAPTCHA-Ma/KaiXu/commit/c9d84e6c87f23e6596354a025a041ccae8258753)
- **V6 · 2026-11-08 · 长说明遮挡设置修复（Room v3）**。完整说明移入概览滚动区，顶部设置标签保持可见，避免既有长说明导致编辑入口不可达。用原 40 行说明实测，新增回归测试，本地 37 项及两项 CI 通过。[PR #16](https://github.com/CAPTCHA-Ma/KaiXu/pull/16) · [提交 49ac1c5](https://github.com/CAPTCHA-Ma/KaiXu/commit/49ac1c54258344fbfde1dd53b68c85065a187757)
- **V5 · 2026-11-08 · 桌面工作区概览（Room v3）**。从本地读取工作区直属任务、固定事件和本人工作时段，提供筛选、原 ID 只读详情及返回位置恢复。让全局任务和工作区使用同一条本地记录。本地 47 项测试及两项 CI 通过。[PR #17](https://github.com/CAPTCHA-Ma/KaiXu/pull/17) · [提交 b5be336](https://github.com/CAPTCHA-Ma/KaiXu/commit/b5be3362b5c9532257a09e9632550060f65fbbc4)
- **V4 · 2026-11-08 · 桌面工作区管理（Room v3）**。增加课程与个人项目统一目录、编辑、归档恢复及课程关联维护，使工作区数据可从桌面界面管理。Windows 退出重启后核对记录、归档及关联保留。[PR #16](https://github.com/CAPTCHA-Ma/KaiXu/pull/16) · [验收记录](docs/04-testing/workspace-management-verification.md)
- **V3 · 2026-11-05 · 本地工作区数据（Room v2→v3）**。Room 自动迁移新增 `workspaces` 表、课程自关联外键及同类型名称唯一索引，为工作区目录和项目归属提供持久化。原任务、事件和个人工作时段表保留，旧归属引用维持原值。旧库迁移及归档规则由 `WorkspaceDatabaseTest` 验证。[PR #12](https://github.com/CAPTCHA-Ma/KaiXu/pull/12)
- **V2 · 2026-11-04 · 任务和日程数据（Room v1→v2）**。手写 `Migration1To2` 保留任务标题，整数 ID 换成 UUID，并为旧行补入默认字段和迁移时的时间值。新增固定事件和个人工作时段表，为本地任务和日程建立统一数据层。旧文件升级由 `ScheduleDatabaseTest` 验证。[PR #7](https://github.com/CAPTCHA-Ma/KaiXu/pull/7) · [迁移代码](apps/client/shared/src/commonMain/kotlin/com/kaixu/data/datasource/local/database/migrations/Migration1To2.kt)
- **V1 · 2026-11-04 · Room v1 基线**。与 v2 同次提交的兼容快照只有整数主键和标题的任务表，用作后续迁移和旧库测试的起点。[PR #7](https://github.com/CAPTCHA-Ma/KaiXu/pull/7) · [v1 结构快照](apps/client/shared/schemas/com.kaixu.data.datasource.local.database.KaiXuDatabase/1.json)
