# 任务业务动作

`TaskUseCases.kt` 将依赖同一个 `TaskRepository` 的任务领域操作集中在一个类中，按职责暴露：

- **领域查询与命令**：
  - `queryTasks`：按工作区、状态、截止条件（基于系统时区 `LocalZone` 判定逾期与边界）、关键字与排序返回 `TaskListSnapshot`（含不变工作区统计与可编辑标志），并支持 `observe(queryFlow)` 随修订号自动重算。
  - `getDetail`：读取单条任务详情 `TaskDetail`，聚合所属工作区、`canEdit` 与关联个人时段列表。
  - `createFromDraft` / `updateFromDraft`：接收 `TaskDraft` 在本地事务中校验本人与进行中工作区归属后创建或编辑（支持 `expectedOriginal` 字段级乐观冲突合并）。
  - `complete`：在同一 `LocalTransaction` 内完成任务并按 `FutureSessionPolicy`（提示确认 / 保留 / 取消未来计划时段）处理 UC11。
  - `reopen` / `delete`：按权限校验将已完成任务恢复为待办或进行中，或软删除任务并脱链时段。
  - `revision`：转发仓储 `StateFlow<Long>` 本地修订信号。
- **兼容既有调用的方法**：`create(task)`、`update(task)`、`deleteById(id)`、`findById(id)`、`listByWorkspace(workspaceId)`、`search(keyword)`、`observe()`、`refresh()`，仍复用 `TaskInput` 校验与事务写入。

写新动作时先说明输入、输出、校验与失败行为；单方法调用如需跨实体原子语义，应在仓储 `writeTransaction` / `LocalTransaction` 中协同实现。

## 扩展任务动作

以上动作统一通过 `AppGraph.taskUseCases` 暴露，ViewModel 与页面逻辑直接注入这一个实例。
聚合类不代表跨方法自动合并事务：多个独立方法连续调用不会自动合成为一次原子操作。

```kotlin
val tasks = graph.taskUseCases
val saved = tasks.createFromDraft(TaskDraft(title = "复习", description = "第三章", estimatedMinutes = 45))
val detail = tasks.getDetail(saved.id)
tasks.updateFromDraft(saved.id, TaskDraft(title = "复习", priority = TaskPriority.HIGH))
val snapshot = tasks.queryTasks(TaskQuery(keyword = "复习"))
tasks.delete(saved.id)
```

## 验证

在 `apps/client` 使用 JDK 21 执行：

```powershell
.\gradlew.bat -PdesktopOnly=true :shared:jvmTest :desktopApp:test
.\gradlew.bat :androidApp:compileDebugKotlin
```
