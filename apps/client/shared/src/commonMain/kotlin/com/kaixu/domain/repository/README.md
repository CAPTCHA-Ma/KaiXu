# 仓储接口

写 `TaskRepository.kt`、未来的 `WorkspaceRepository.kt` 等接口，定义上层需要什么数据能力，不说明用 HTTP 还是缓存。

当前 TaskRepository 公开已确认任务的 StateFlow、`refresh()` 和 `create(title)`。UseCase 依赖它；data/repository 中的实现类实现它。

接口使用 domain model，不暴露 TaskDto、HttpResponse、SQL Cursor。这里不写请求地址、JSON 解析或数据库语句。同一个接口可有正式实现和测试替身。

## 新增接口时

可增加 WorkspaceRepository.kt、ScheduleRepository.kt。声明方法的输入、返回值和失败语义；实现文件放 data/repository，测试替身放 commonTest。不要为 Android 与桌面复制内容一致的仓储接口。
