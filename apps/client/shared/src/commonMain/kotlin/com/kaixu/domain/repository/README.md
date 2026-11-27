# 仓储接口

定义上层用例所需的数据与事务能力（如 `TaskRepository.kt`、`FixedEventRepository.kt`、`WorkSessionRepository.kt`、`FocusRepository.kt`），不暴露 Room Entity、SQL Cursor、DTO 或 HTTP 细节。

核心仓储统一遵循「受控写范围（`*WriteScope`）+ `writeTransaction` + 共享修订信号 `revision: StateFlow<Long>`」模式：
- `TaskRepository`（`TaskWriteScope`）：公开已确认任务的 `StateFlow`、`revision`、CRUD、搜索、工作区与本人工作时段读取，以及跨表完成/删除联动写事务。
- `FixedEventRepository`（`FixedEventWriteScope`）：公开已确认固定事件的 `StateFlow`、`revision`、重复序列例外（含软删除取消占位）读取、工作区/任务/本人时段上下文读取与写事务。
- `WorkSessionRepository`（`WorkSessionWriteScope`）：绑定当前本人身份（不暴露他人私人时段），公开 `revision`、按 ID/任务/时间窗口读取、批量创建/更新/软删除、本人固定事件与重复例外读取、专注会话与累计时长读取及 `writeTransaction`。
- `FocusRepository`（`FocusWriteScope`）：绑定当前本人身份，公开 `revision`、专注会话与闭合有效片段读取/追加、累计时长汇总，以及支持在同一本地事务内原子懒补写 `WorkSession.focusTargetSeconds` 与创建 `FocusSession` 的 `writeTransaction`。片段不单独提供修改或删除接口。

输入校验由 `TaskInput`、`FixedEventForm` 与 `SchedulingInput` 统一把守；更新或删除未命中活动记录返回 `false`，按 ID 查询未命中返回 `null`，存储异常与协程取消原样抛出。

`LocalTransaction` 由本地组合根（`LocalDataSources.transaction`）实现，支持跨表读检查与写入的本地数据库原子性；`LocalDataRevision` 在事务成功提交且发生实际数据修改后递增修订号，回滚或只读检查不递增。实现类位于 `data/repository`，测试替身位于 `commonTest/kotlin/com/kaixu/testing`。
