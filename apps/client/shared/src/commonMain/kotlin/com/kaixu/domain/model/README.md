# 业务模型

按业务分包，写纯 Kotlin `data class`、`enum class`、值对象等。目前 `tasks/Task.kt` 是只有 ID 和标题的最小对象。

未来 Task、FixedEvent、WorkSession 应分别建模：任务截止时间不是执行时段；共享任务不包含他人的私人安排。按已确认需求逐步加入字段，不先造一份覆盖全 SRS 的巨型对象。

业务模型不带 `@Composable`、JSON 字段命名或数据库表注解。API DTO、缓存 Entity、页面 UiState 分别放在各自层。

## 适宜存放的文件

可放 Workspace.kt、TaskStatus.kt、WorkSession.kt 等业务数据类、枚举和值对象；任务详情页显示模型若带有格式化文案或选中状态，应放 presentation/state，而不是放入共享实体。
