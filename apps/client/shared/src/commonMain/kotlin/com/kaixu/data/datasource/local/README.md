# 本地持久化数据源

桌面与 Android 共用 Room 数据库定义。版本 5 包含 tasks、fixed_events、work_sessions、workspaces、resources、focus_sessions 和 focus_segments 七张表。
桌面工作区数据源用于未登录用户的本地课程和个人项目。

## 表与字段

| Entity | 内容 |
| --- | --- |
| TaskEntity | UUID、标题说明、工作区、创建者与负责人、状态、优先级、日期或时刻截止、总估时、阻碍、完成时间、确认后的来源摘录与链接 |
| FixedEventEntity | UUID、工作区、创建者、标题说明、起止、提醒提前量，以及周期规则与例外预留字段 |
| WorkSessionEntity | UUID、所有者、任务与可选阶段、计划起止、本人说明、状态、锁定与重叠确认、脱链信息、个人计时目标 |
| WorkspaceEntity | UUID、本地所有者、类型、名称、说明、可选关联课程、归档状态与归档时间 |
| ResourceEntity | 工作区、创建者、名称、说明及链接或文本资料 |
| FocusSessionEntity | 计划时段外键、所有者、实际开始与结束、运行或暂停或结束或中断状态、个人反馈 |
| FocusSegmentEntity | 会话外键、稳定提交标识、有效开始与结束、前台或后台类型 |

任务、事件、计划时段、工作区、资料还包含创建和修改时间、修改人、serverVersion、localRevision、syncState。
任务、事件、计划时段及资料有软删除时间。计时会话仅保存本地创建和修改时间；有效片段只追加，不提供修改或删除。
UUID 存 TEXT；精确时刻存 UTC 毫秒 Long；日期存 YYYY-MM-DD，不转换为午夜时间戳。
TaskPriority 暂定 LOW/NORMAL/HIGH；对接服务端前需确认统一枚举。
新增记录默认 LOCAL_ONLY；预留同步字段不代表已实现同步。
独立本地模式暂使用保留身份 LOCAL_ACTOR_ID；local_meta/账号接入时应替换为空间身份，不能作为权限凭据。

## 分层与 DAO

- database/：数据库声明与公共 builder。平台创建入口在 jvmMain / androidMain，宿主持有并关闭实例。
- entity/：本地结构与基本值校验；不直接交给 UI。
- dao/：基础读写和必要的本地事务。
- RoomTaskDataSource：直接实现 TaskDataSource，提供完整 CRUD 和标题录入接口。
- RoomFixedEventDataSource / RoomWorkSessionDataSource：实现事件和工作时段数据源接口。
- RoomFocusDataSource：保存实际计时会话与已确认片段，累计全部关联片段，包含计划窗口外的有效时间。
- RoomWorkspaceDataSource 使用 WorkspaceDao 的事务创建、编辑工作区和维护课程关联，提供归档、恢复和目录查询。
- LocalDataSources(database) 向上层提供共用同一数据库的数据源。
- data/mapper/：本地实体到领域对象的转换。

任务、事件和时段的 DAO 均提供 list、findById、insert、update、deleteById。
任务支持 listByWorkspace 和 updateTitle；事件支持 listByWorkspace；时段支持 listByTask。
事件和时段的 listInRange(ownerId, start, end) 使用半开区间；事件按创建者过滤，时段按所有者过滤。
该查询不是完整共享日程聚合，也不执行授权。

所有常规查询排除软删除记录；删除返回受影响行数，重复删除返回 0。
update 保留原创建时间，更新 updatedAt 并递增 localRevision；任务不可变更归属或创建者，时段不可转移所有者。
TaskDao.deleteById 在同一事务中中断活动会话、对关联计划时段脱链并软删除任务，保留计划时间、本人说明与计时历史。
计划时段取消、完成、脱链或软删除时同样中断活动会话。阶段、资料关联与同步队列接入后仍需扩展完整删除命令。

WorkSessionEntity.taskId 对 tasks.id 有 RESTRICT 外键；新增/修改时段还检查任务未软删除。
workspaces.linked_course_id 引用同表课程 ID。任务和事件沿用 v2 的 workspace_id，以便打开已有独立记录和历史引用。用户、阶段、周期规则也沿用 ID 引用。
状态、时间先后、截止互斥、日期格式、估时、完成时间及计时字段一致性在 Entity 构造时检查；
不要绕过 DAO 直接写 SQL。阶段归属、权限、排期冲突确认、任务完成与本人未来时段处理仍需业务命令实现。
周期字段仍是预留。计时记录已支持存取，尚未接入手机计时器或在线同步。
计划批量事务、会话与有效片段规则见 [私人排期本地数据链路](../../../../../../../../../../../docs/03-design/private-schedule-datalink.md)。

## 数据库文件与迁移

默认文件为 LOCALAPPDATA/KaiXu/local/kaixu.db；无 LOCALAPPDATA 时使用
用户目录下 .local/share/KaiXu/local/kaixu.db。Android 使用应用私有 databases/kaixu.db。
测试显式使用独立临时文件。

shared/schemas/ 保留各版本的 Room 结构快照。
Migration1To2 将旧整数任务主键转换为 UUID，保留标题并创建另外两表及索引。
旧版没有创建时间，迁移时补入迁移时刻并按旧 ID 顺序补入毫秒偏移以保留列表顺序，不代表真实创建时间。
版本 1 不存在其他关联表，因此无需重映射外部引用；旧整数 ID 不再保留。
DatabaseFactory 注册 v1 到 v2 的迁移。KaiXuDatabase 声明的 Room 自动迁移从 v2 升至 v3 新增工作区，v3 至 v4 新增资料，v4 至 v5 新增实际计时会话和有效片段并删除计划冻结窗口两列。首次新建使用版本 5。
后续修改需升版本并新增迁移，不能改写已交付历史迁移或通过删库代替迁移。

## 简单调用

上层通过 TaskDataSource、FixedEventDataSource、WorkSessionDataSource 操作领域对象，
无需引用 DAO 或 Entity。完整新增传入带稳定 UUID 的领域对象；任务还可用 createTask(title) 自动分配 UUID。
update 接受完整业务对象（建议查询后 copy），返回 Boolean；不存在或已删除时返回 false，
不隐式插入。重复 ID 新增、非法字段、非法关联或不允许的归属变更会抛出异常。
delete 同样返回 Boolean，重复删除返回 false。创建时间和同步元数据不交给上层修改。
存储规则在 Entity/DAO 边界校验；领域对象构造本身不保证可以保存。

```kotlin
val sources = LocalDataSources(database)
val task = sources.tasks.createTask("复习数学")
val session = sources.workSessions.createWorkSession(
    WorkSession(
        id = "e3eb55a1-5b43-4d84-b3ce-1c3b34f355aa", // 实际调用由上层分配新 UUID
        taskId = task.id,
        startsAt = startMillis,
        endsAt = endMillis,
        note = "复习第一章",
    )
)
sources.workSessions.updateWorkSession(session.copy(note = "复习第二章"))
sources.tasks.deleteTaskById(task.id) // 时段脱链保留
val retained = sources.workSessions.findWorkSessionById(session.id)
```

工作区上层从 `LocalDataSources(database).workspaces` 访问 `WorkspaceDataSource`。
`createWorkspace` 返回带 UUID 的课程或项目，`listWorkspaces()` 默认列出进行中的记录，`listProjectsForCourse(courseId)` 按同一个课程 ID 查询关联项目。
工作区、任务和日程领域对象分别位于 domain.model.workspaces、domain.model.tasks 和 domain.model.scheduling。
LocalDataSources 不持有独立缓存，不负责关闭数据库；宿主应在退出时关闭数据库实例，
再次使用同一路径打开即可恢复数据。各数据源的查询返回即时快照，暂无变更订阅接口。

需要直接维护存储层时才使用 DAO：

```kotlin
val task = TaskEntity(title = "复习数学", estimatedMinutes = 60)
database.taskDao().insert(task)
val session = WorkSessionEntity(
    taskId = task.id,
    startsAt = startMillis,
    endsAt = endMillis,
    note = "复习第一章",
)
database.workSessionDao().insert(session)
database.workSessionDao().update(session.copy(note = "改为复习第二章"))
database.taskDao().deleteById(task.id) // 时段脱链保留
```

## 自动验证

在 apps/client 下使用 JDK 21 运行：

```powershell
.\gradlew.bat -PdesktopOnly=true :shared:jvmTest :desktopApp:classes
```

LocalDataSourcesTest 通过公开数据源接口验证三类对象的完整业务字段读写、重复 ID、
更新/删除不存在记录、工作区/任务/所有者筛选、半开时间区间和非法写入。
测试对同一临时数据库文件关闭并重新创建数据库及数据源实例，分别验证新增保留、
修改保留、任务删除后的时段脱链保留，以及软删除记录在重新打开后仍不可见。
这是文件持久化和连接重建验证；未自动操作桌面窗口执行应用退出/启动。
原有 RoomDatabaseTest、ScheduleDatabaseTest 继续覆盖仓储兼容、DAO 事务回滚和 v1→v2 迁移。
WorkspaceDatabaseTest 覆盖工作区归档恢复、关联修改、并发重名写入、写入失败重试、文件重开及 v2 数据库升级。
