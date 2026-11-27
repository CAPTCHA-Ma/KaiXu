# 客户端业务动作

按业务领域分包，构造函数只依赖领域仓储接口与跨平台 `LocalZone`，不引用 `DataSource`、Room 或平台 UI 状态。复杂的独立动作采用「动词＋对象＋Command/QueryUseCase」并以 `operator fun invoke()` 作为入口，按模块统一收口至门面类供上层注入：

- **任务域（`tasks/TaskUseCases.kt`）**：聚合 `queryTasks`（含 `observe`）、`getDetail`、`createFromDraft`、`updateFromDraft`（含字段级并发合并）、`complete`（事务内处理未来计划时段 `KEEP_FUTURE` / `CANCEL_FUTURE`）、`reopen` 与 `delete`（原子中断活动计时并脱链工作时段）。
- **固定事件与日历窗口域（`scheduling/FixedEventUseCases.kt`）**：聚合 `getDetail`、`create`、`update`、`delete`（支持单次/序列与重复课次 `THIS_OCCURRENCE` / `THIS_AND_FUTURE` 作用域及带真实任务标题的冲突确认）与 `queryCalendar`（基于 `LocalZone`、`Recurrence.expand` 与 `CalendarDayLayout` 的日/周平铺日程切片查询及 `observe`）。
- **个人工作时段域（`scheduling/WorkSessionUseCases.kt`）**：
  - `WorkSessionAccessPolicy` 集中判定本人身份、任务可排期条件与时段可编辑/改期/状态流转能力（脱链时段只读可删，保留历史专注时长）。
  - `WorkSessionConflictChecker` 在写事务内通过 `Recurrence.expand(..., zone)` 展开本人固定事件（含跨零点回溯、改期例外与取消占位，排除他人共享只读事件），结合本人未取消时段、批量草稿内部重叠与 `TaskDeadline` 生成带稳定 `confirmationKey` 的 `WorkSessionConfirmation`。
  - 统一门面聚合 `create`（单条/批量创建与 ID 幂等重试）、`update`（已有计时历史后锁定 `focusTargetSeconds`）、`reschedule`（面向拖拽/拉伸的轻量改期）、`changeStatus`（`PLANNED -> COMPLETED | CANCELLED`，绝不自动完成关联任务）、`delete`、`getDetail`（含 `observe`）与 `queryByTask`（按任务汇总计划/完成/取消数量、有效计划时长与专注时长，含 `observe`）。
- **专注计时域（`scheduling/FocusUseCases.kt`）**：统一聚合 `start`（同一写事务内原子懒补写默认 `focusTargetSeconds` 与创建会话）、`pause`、`resume`（重检时段与任务可执行性）、`finish`、`recordSegment`（基于 `clientSegmentId` 幂等追加闭合片段）与 `getSummary`（含 `observe`）。`WorkSessionCommands.kt` 与 `FocusCommands.kt` 保留兼容包装类并委托至新版用例。
- **首页三日瀑布流聚合（`scheduling/QueryHomeAgendaUseCase.kt`）**：基于 `TaskRepository`、`FixedEventRepository`、`WorkSessionRepository`、`LocalZone` 与 `TaskDeadline` 聚合昨天至明天三日日程与本人进行中工作区，去重合并共享 `revision` 驱动 `observe` / `observeFeed` 响应式刷新；原 `AgendaUseCase` 作为薄兼容适配器保留，内部委托至 `QueryHomeAgendaUseCase`。

四组门面在 `AppGraph` 中共享同一 `LocalTransaction` 与 `LocalDataRevision`；跨表操作仅代表本地 Room 事务，未来接入远程接口时仍由服务端单次事务命令保证跨端一致性。
