# 客户端业务动作

按业务分包，命名采用“动词＋对象＋UseCase”，每个类完成一个动作。构造函数接收仓储接口，`operator fun invoke()` 作为入口。

UseCase 类似你熟悉的 Service 中“一段业务流程”：校验输入、组合所需数据、调用仓储。这里不存页面可变状态、不设置弹窗文字、不直接调用 HTTP。

客户端调用多个仓储不能形成服务端事务。例如“完成任务并取消未来时段”应由后端单次事务接口实现，UseCase 只发一个带用户选择的命令。

本示例保留创建、观察、刷新三类以便沿统一链条学习。简单读取在更小项目中可直接由 ViewModel 调仓储；UseCase 不是 MVVM 的强制层，不必为每个 getter 增加空壳。

`scheduling/WorkSessionCommands.kt` 提供计划时段批量创建、编辑、个人状态变更、删除和日程聚合，
冲突及截止提示以快照确认后再次检查。`scheduling/FocusCommands.kt` 提供计时启动、暂停、继续、
结束、有效片段提交和汇总。两组新用例只依赖领域仓储接口与事务接口，不引用 DataSource 或 Room。
跨仓储的本地操作可以使用同一 Room 事务；远程操作仍需要服务端事务，不能跨网络持有本地事务。

## 适宜存放的文件

可按 tasks、scheduling、workspaces 分包，添加 UpdateTaskStatusUseCase.kt、RescheduleSessionUseCase.kt 等动作。不同端的 ViewModel 调用同一个动作；导航、弹窗开关和用户焦点不属于动作输入。
