# 仓储实现

`data/repository` 实现 `domain/repository` 中的各仓储契约，注入本地数据源、跨表 `LocalTransaction` 与共享修订信号 `LocalDataRevision`：

- `DefaultTaskRepository`：维护已确认任务快照 `StateFlow`，使用 `Mutex` 串行化写事务并在提交成功后原子应用暂存变更、递增修订号。
- `DefaultFixedEventRepository`：维护已确认固定事件排序快照，纯读不加互斥锁以避免写事务内重入死锁，写事务提交成功后统一更新快照并递增修订号。
- `DefaultWorkSessionRepository`：绑定本人身份协调工作时段、任务、工作区、固定事件（含重复例外与取消占位）及专注计时数据源；不持有独立可变列表缓存（避免任务删除脱链或完成取消后产生陈旧快照），纯读不加锁，写事务仅在实际发生修改且成功提交后递增共享修订号。
- `DefaultFocusRepository`：绑定本人身份协调专注会话、有效计时片段及关联工作时段数据源；支持在同一写事务内原子更新默认 `focusTargetSeconds` 与写入 `FocusSession`，幂等重试不重复递增修订号，异常回滚时修订号保持不变。

网络失败、存储异常或并发校验失败均原样抛出，绝不发布虚假的成功快照或递增修订号。
