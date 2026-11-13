# UC08 固定事件管理验证

> 验证日期：2026-11-12  
> 分支：`feat/7-Events-and-Schedule-Browsing-Flow`  
> 范围：UC08、UC08.1–UC08.4（本地优先部分）

## 1 实现范围

| 用例 | 实现内容 |
| --- | --- |
| UC08 入口与前提 | 桌面端日程页提供「新建固定事件」；个人事件只读写本地 Room，不需要登录或联网；Android 端只读日程，无任何创建/编辑/删除入口 |
| UC08.1 字段 | 标题、说明、开始时间、结束时间、工作区（进行中的课程/项目或「独立事件」） |
| UC08.2 校验与冲突 | 结束必须晚于开始；保存前展开本人在窗口内的固定事件与工作时段，半开区间判断重叠；相邻首尾不算冲突；发现重叠时返回冲突快照，用户「返回修改」或「仍然保存」；确认绑定当次输入，改动后必须重新确认 |
| UC08.3 编辑与删除 | 编辑更新同一记录并保留 ID、创建者、创建时间与同步元数据；删除为软删除，需二次确认；保存/删除后日程日视图与周视图重新读取；重复事件删除时可选择「仅本次」或「本次及后续」 |
| UC08.4 每周重复与提醒 | 星期多选 + 重复结束日期（含当天），查询时展开，不物化课次；编辑课次区分「仅本次」（例外行）与「本次及后续」（截断旧序列 + 新建后续序列）；站内提醒提前量可设置并随记录保存，在详情中展示，本期不派发系统通知 |

## 2 关键文件

| 层 | 文件 |
| --- | --- |
| 领域模型与展开 | `domain/model/scheduling/FixedEvent.kt`、`Recurrence.kt` |
| 校验 | `domain/validation/scheduling/FixedEventForm.kt`、`ShanghaiTime.kt` |
| 写入口 | `domain/usecase/scheduling/FixedEventSchedule.kt` |
| 数据层 | `data/datasource/local/entity/FixedEventEntity.kt`、`dao/FixedEventDao.kt`、`mapper/FixedEventEntityMapper.kt` |
| 数据库 | `KaiXuDatabase.kt`（v6，AutoMigration 5→6）、`schemas/.../6.json` |
| 装配 | `app/di/LocalSchedulingGraph.kt` |
| 桌面 UI | `presentation/ui/scheduling/LocalScheduleRoute.kt`、`FixedEventEditorDialog.kt`、`records/LocalRecordRoute.kt` |
| Android UI | `androidApp/.../ui/scheduling/ScheduleDayRoute.kt`、`navigation/AndroidNavigation.kt` |

## 3 数据库变更

`fixed_events` 新增两列，Room AutoMigration 从 v5 升到 v6：

- `repeat_weekdays TEXT`：逗号分隔的 ISO 星期序号（如 `"1,3,5"`），`null` 表示一次性事件。
- `repeat_until TEXT`：重复结束日期（含当天），与 `repeat_weekdays` 同时为空或同时非空。

序列行保存规则；例外行通过既有 `series_id + original_date + is_override` 表达，「仅本次删除」写入软删除的例外行作为取消占位。

## 4 自动化验证

```bash
cd apps/client
./gradlew -PdesktopOnly=true :shared:jvmTest :desktopApp:test
./gradlew :androidApp:compileDebugKotlin
```

结果：

- `:shared:jvmTest`：23 个测试套件、120 个测试，0 失败。
- `:desktopApp:test`：7 个测试套件、23 个测试，0 失败。
- `:androidApp:compileDebugKotlin`：通过。

新增覆盖：

| 测试 | 覆盖点 |
| --- | --- |
| `RecurrenceTest` | 跨周展开、结束日期含当天、取消例外隐藏、例外内容覆盖、一次性事件窗口、草稿课次 |
| `FixedEventScheduleTest` | 创建工作区归属、空标题/倒置时间、归档/他人工作区拒绝、重叠需确认、相邻不冲突、确认失效、重复展开、仅本次编辑、本次及后续截断、仅本次删除幂等、后续删除、整条序列删除、他人事件拒绝、重复规则校验 |
| `ShanghaiTimeTest` | 上海本地日期、跨月跨年加减、ISO 星期、非法日期、原时刻还原 |
| `FixedEventEditTimeTest` | 编辑框时间文本严格解析与秒/毫秒保留 |

## 5 手工验收步骤

1. 桌面打开「日程」，点「新建固定事件」，只填标题与起止时间 → 保存后日视图出现该事件。
2. 选择关联一个进行中的工作区保存 → 详情显示工作区名称；归档该工作区后再编辑 → 提示只能关联进行中的工作区。
3. 新建一条与已有事件重叠的事件 → 出现冲突对话框；选「返回修改」不写入；选「仍然保存」写入。
4. 新建结束时间早于开始时间 → 提示错误且不写入。
5. 编辑事件标题与时间 → 保存后列表刷新，ID 不变；重启应用后仍存在。
6. 删除事件 → 二次确认；确认后日/周视图不再显示，重复删除不报错。
7. 新建每周重复事件（如周一、周三，结束日期两周后）→ 周视图按规则显示各课次；结束日期之后不再显示。
8. 编辑某次课次选「仅本次」→ 只有该次变化；选「本次及后续」→ 该次及之后使用新内容，更早课次不变。
9. 删除某次课次选「仅本次」→ 该次消失，其余保留；选「本次及后续」→ 之后课次消失。
10. 设置站内提醒提前量 → 详情显示「站内提醒：提前 N 分钟」，不弹出系统通知。
11. Android 打开日程 → 只读展示当日固定事件，无新建/编辑/删除按钮。

## 6 已知限制

- **账号模式排队同步未实现**：仓库尚无账号会话、同步队列与服务端 `/events` 接口，因此 UC08.3 中「账号模式下排队同步，服务端确认后其他在线终端刷新可见」只保留了字段语义（`sync_state` 默认 `LOCAL_ONLY`），没有真实网络调用。接入账号与同步链路时需要：写入后置 `PENDING`、Outbox 记录、服务端确认后改 `SYNCED` 并触发其他终端拉取。
- **站内提醒不派发**：只保存 `reminder_offset_minutes` 并在详情展示，没有通知中心或调度器。
- **Android 只读数据来自本地库**：当前没有在线接口，Android 读取本机数据库中的固定事件；接入服务端后应改为在线查询。
- **共享事件未接入**：冲突检查只覆盖本人记录，不含协作共享事件。
