# 仓储实现

`DefaultTaskRepository.kt` 实现 domain 中的 TaskRepository，注入 TaskDataSource。它负责刷新列表、保存成功后发布新快照，并用 Mutex 协调读写。

业务输入规则由领域 TaskInput 统一定义，UseCase 和仓储入口共同校验，防止直接调用仓储绕过规则。仓储负责完整 Task 的创建、编辑、软删除、查找、工作区筛选和标题/描述搜索，并仅在写入成功后更新 tasks。未来接入远程与缓存时，这里决定在线读取和个人缓存的更新；源返回的 DTO 在数据层转换后才进入业务层。

网络失败、超时和冲突不得发布虚假的成功数据。共享内容必须在线验证权限；离线仅提供当前账号的个人缓存，不增加离线写队列。账号切换后旧请求不能把旧账号结果写入新会话。

## 适宜存放的文件

`DefaultWorkSessionRepository` 绑定本人身份，协调计划时段、任务、工作区及固定事件数据源。
`DefaultFocusRepository` 协调本人会话与片段，两者都不保存独立列表缓存。
上层业务用例通过 `SchedulingTransaction` 在同一 Room 写事务里复查约束、追加片段和变更状态。

可加入 DefaultWorkspaceRepository.kt、DefaultScheduleRepository.kt 等仓储实现；一个实现可协调多个来源。当前 Mutex 仅保护本对象的访问，不能替代跨端版本验证或服务端事务。
