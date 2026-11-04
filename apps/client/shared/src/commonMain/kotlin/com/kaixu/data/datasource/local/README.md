# 本地持久化数据源

桌面与 Android 均使用 Room 保存业务数据，数据库版本为 2。
三张表依据工作区的《KaiXu数据库设计》3.6、3.10、3.11 和 4.1 节实现。
本地表统一沿用复数命名 tasks、fixed_events、work_sessions。

## 表与字段

| Entity | 内容 |
| --- | --- |
| TaskEntity | UUID、标题说明、工作区、创建者与负责人、状态、优先级、日期或时刻截止、总估时、阻碍、完成时间、确认后的来源摘录与链接 |
| FixedEventEntity | UUID、工作区、创建者、标题说明、起止、提醒提前量，以及周期规则与例外预留字段 |
| WorkSessionEntity | UUID、所有者、任务与可选阶段、起止、本人说明、状态、锁定与重叠确认、脱链信息、冻结计时窗口与目标 |

各表还包含创建/修改/软删除时间、修改人、serverVersion、localRevision、syncState。
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
- LocalDataSources(database)：组合三个共享同一数据库的接口实例，供仓储/业务层注入；两端 AppContainer 均提供该入口。
- data/mapper/：本地实体到领域对象的转换。

三个 DAO 都提供 list、findById、insert、update、deleteById。
任务支持 listByWorkspace 和 updateTitle；事件支持 listByWorkspace；时段支持 listByTask。
事件和时段的 listInRange(ownerId, start, end) 使用半开区间；事件按创建者过滤，时段按所有者过滤。
该查询不是完整共享日程聚合，也不执行授权。

所有常规查询排除软删除记录；删除返回受影响行数，重复删除返回 0。
update 保留原创建时间，更新 updatedAt 并递增 localRevision；任务不可变更归属或创建者，时段不可转移所有者。
TaskDao.deleteById 在同一事务中软删除任务并对关联时段脱链，保留时间和本人说明。
任务阶段、资料、活动计时和同步队列尚不存在，未来接入后必须扩展完整删除命令。

WorkSessionEntity.taskId 对 tasks.id 有 RESTRICT 外键；新增/修改时段还检查任务未软删除。
工作区、用户、阶段、周期规则仅保留 ID，待对应表实现后添加外键。
状态、时间先后、截止互斥、日期格式、估时、完成时间及计时字段一致性在 Entity 构造时检查；
不要绕过 DAO 直接写 SQL。阶段归属、权限、排期冲突确认、任务完成与本人未来时段处理仍需业务命令实现。
周期字段和计时字段只是存储准备，尚未实现对应功能。

## 数据库文件与迁移

默认文件为 LOCALAPPDATA/KaiXu/local/kaixu.db；无 LOCALAPPDATA 时使用
用户目录下 .local/share/KaiXu/local/kaixu.db。Android 使用应用私有 databases/kaixu.db。
测试显式使用独立临时文件。

shared/schemas/ 保留 1.json 与 2.json。
Migration1To2 将旧整数任务主键转换为 UUID，保留标题并创建另外两表及索引。
旧版没有创建时间，迁移时补入迁移时刻并按旧 ID 顺序补入毫秒偏移以保留列表顺序，不代表真实创建时间。
版本 1 不存在其他关联表，因此无需重映射外部引用；旧整数 ID 不再保留。
迁移在 DatabaseFactory 注册，打开旧库时自动执行；首次新建直接使用版本 2。
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

领域对象位于 domain.model.tasks 和 domain.model.scheduling；业务状态枚举也在领域层，
原 entity 包内枚举名称通过 typealias 保持源码兼容。数据库仍为版本 2，表结构不变。
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
