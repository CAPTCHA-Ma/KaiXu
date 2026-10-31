# 任务业务动作

- `CreateTaskUseCase.kt`：复用 TaskTitle 校验，规范化标题，再调用仓储 create。无效输入不会写入数据源。
- `ObserveTasksUseCase.kt`：返回仓储的已确认任务流，不额外维护一份列表。
- `RefreshTasksUseCase.kt`：请求重新读取，异常交给 ViewModel 展示。

写新动作时先说明输入、输出、校验与失败行为。未来 `CompleteTaskUseCase` 应接收“保留还是取消我的未来时段”，调用后端原子命令，不能先本地改状态再逐条删除时段。

## 扩展任务动作

可加入 UpdateTaskUseCase.kt、CompleteTaskUseCase.kt、DeleteTaskUseCase.kt；输入采用业务 ID、版本和确认选择，输出采用业务结果。当前只有 Create、Observe、Refresh 的实际实现，其余名称是规划示例。
