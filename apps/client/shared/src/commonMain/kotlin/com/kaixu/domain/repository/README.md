# 仓储接口

写 `TaskRepository.kt`、未来的 `WorkspaceRepository.kt` 等接口，定义上层需要什么数据能力，不说明用 HTTP 还是缓存。

当前 TaskRepository 公开已确认任务的 StateFlow、`refresh()`、`create(task): Task`、`update(task): Boolean`、`deleteById(id): Boolean`、`findById(id): Task?`、`listByWorkspace(workspaceId): List<Task>` 和 `search(keyword): List<Task>`。UseCase 依赖它；data/repository 中的实现类实现它。

创建和编辑复用完整领域 Task。id 默认生成 UUID；编辑以 id 定位，完整替换其余业务字段，包括工作区和创建者，可空字段传 null 表示清空。创建/更新均校验 TaskInput；Kotlin 非空类型约束必填字段，字符串身份字段还须非空白。标题沿用 120 字符限制并去除首尾空白；截止日期与精确截止互斥，日期必须有效，预计分钟数不得为负，COMPLETED 与 completedAt 必须成对出现。

更新和删除没有命中活动记录返回 false，按 ID 查询未命中返回 null。重复 ID、存储错误及协程取消原样抛出。删除使用数据源软删除事务并解除工作时段关联。创建时间、同步版本、删除标记等持久化元数据仍由数据层管理。

查询始终读取数据源、排除软删除任务。关键字匹配标题和描述，忽略大小写，空白关键字返回全部活动任务。筛选查询不替换全局 tasks；refresh 发布完整列表，写入成功后才更新快照。tasks 是当前仓储实例已确认的快照，外部写入需 refresh 才会完整反映。

接口使用 domain model，不暴露 TaskDto、HttpResponse、SQL Cursor。这里不写请求地址、JSON 解析或数据库语句。同一个接口可有正式实现和测试替身。

## 新增接口时

可增加 WorkspaceRepository.kt、ScheduleRepository.kt。声明方法的输入、返回值和失败语义；实现文件放 data/repository，测试替身放 commonTest。不要为 Android 与桌面复制内容一致的仓储接口。
