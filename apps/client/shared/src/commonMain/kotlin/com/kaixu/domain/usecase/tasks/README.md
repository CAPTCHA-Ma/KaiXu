# 任务业务动作

- `CreateTaskUseCase.kt`：接收完整 Task，复用 TaskInput 校验、规范化标题，返回保存的 Task。
- `UpdateTaskUseCase.kt`：接收完整 Task，校验并按 id 替换所有业务字段；未命中返回 false。
- `DeleteTaskUseCase.kt`：按非空 id 软删除；未命中返回 false。
- `FindTaskByIdUseCase.kt`：按非空 id 查找；未命中返回 null。
- `ListTasksByWorkspaceUseCase.kt`：按非空工作区 id 筛选。
- `SearchTasksUseCase.kt`：按标题/描述关键字查询，空关键字列出全部活动任务。
- `ObserveTasksUseCase.kt`：返回仓储的已确认任务流，不额外维护一份列表。
- `RefreshTasksUseCase.kt`：请求重新读取，异常交给 ViewModel 展示。

写新动作时先说明输入、输出、校验与失败行为。未来 `CompleteTaskUseCase` 应接收“保留还是取消我的未来时段”，调用后端原子命令，不能先本地改状态再逐条删除时段。

## 扩展任务动作

以上动作均通过 AppGraph 暴露。状态与完成时间可以通过 UpdateTaskUseCase 一起编辑；涉及未来时段处置的独立 CompleteTaskUseCase 仍待后端原子命令接入。

```kotlin
val saved = graph.createTask(Task(title = "复习", description = "第三章", estimatedMinutes = 45))
val found = graph.findTaskById(saved.id)
graph.updateTask(saved.copy(description = null, priority = TaskPriority.HIGH))
val matches = graph.searchTasks("复习")
graph.deleteTask(saved.id)
```
