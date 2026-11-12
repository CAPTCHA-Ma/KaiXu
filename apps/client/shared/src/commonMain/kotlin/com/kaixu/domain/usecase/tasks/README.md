# 任务业务动作

`TaskUseCases.kt` 将依赖同一个 TaskRepository 的任务操作集中在一个类中，通过明确的方法名调用。

- `create(task)`：接收完整 Task，复用 TaskInput 校验、规范化标题，返回保存的 Task。
- `update(task)`：接收完整 Task，校验并按 id 替换所有业务字段；未命中返回 false。
- `deleteById(id)`：按非空 id 软删除；未命中返回 false。
- `findById(id)`：按非空 id 查找；未命中返回 null。
- `listByWorkspace(workspaceId)`：按非空工作区 id 筛选。
- `search(keyword)`：按标题/描述关键字查询，空关键字列出全部活动任务。
- `observe()`：返回仓储的已确认任务流，不额外维护一份列表。
- `refresh()`：请求重新读取，异常交给 ViewModel 展示。

写新动作时先说明输入、输出、校验与失败行为。未来 `CompleteTaskUseCase` 应接收“保留还是取消我的未来时段”，调用后端原子命令，不能先本地改状态再逐条删除时段。

## 扩展任务动作

以上动作统一通过 `AppGraph.taskUseCases` 暴露，ViewModel 注入这一个实例。
状态与完成时间可以通过 `update` 一起编辑；涉及未来时段处置的完整任务完成流程仍待原子命令接入。
聚合类不代表合并事务：多个方法连续调用并不会自动成为一次原子操作。

```kotlin
val tasks = graph.taskUseCases
val saved = tasks.create(Task(title = "复习", description = "第三章", estimatedMinutes = 45))
val found = tasks.findById(saved.id)
tasks.update(saved.copy(description = null, priority = TaskPriority.HIGH))
val matches = tasks.search("复习")
tasks.deleteById(saved.id)
```

## 验证

在 `apps/client` 使用 JDK 21 执行：

```powershell
.\gradlew.bat -PdesktopOnly=true :shared:jvmTest :desktopApp:test
.\gradlew.bat :androidApp:compileDebugKotlin
```

本次迁移通过 76 项共享层 JVM 测试及 15 项桌面测试，Android debug Kotlin 编译通过。
原有输入校验、CRUD、搜索筛选、订阅刷新、保存失败与取消、持久化及任务删除后的时段脱链继续由既有测试覆盖。
