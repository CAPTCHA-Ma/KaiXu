# 数据源接口与实现

TaskDataSource 声明任务的完整 CRUD、按 ID 查询和工作区筛选，并保留 createTask(title) 快捷创建。
FixedEventDataSource 和 WorkSessionDataSource 分别声明固定事件与工作时段的访问能力。
FocusDataSource 提供实际计时会话的存取、有效片段追加与累计时间查询。
上述接口均返回领域对象，不把 Room Entity 交给业务层。

local/ 提供 RoomTaskDataSource、RoomFixedEventDataSource、RoomWorkSessionDataSource、RoomFocusDataSource，
由 LocalDataSources(database) 统一装配。两端宿主打开本端数据库并将任务数据源注入 AppGraph。
AppGraph 没有默认数据源，正式代码中已移除内存数据源。

remote/ 仍是远程访问预留。未来如需同步，可定义符合服务端协议的独立远程接口，
由 Repository 或同步组件协调；无需强制远程接口与本地 CRUD 完全相同。

平台数据库工厂分别位于 jvmMain 和 androidMain；commonMain 保存公共接口、实体、DAO 与实现。
领域和 ViewModel 单元测试中的延迟/失败控制位于 commonTest 的 TestTaskRepository；
桌面导航与持久化集成测试使用独立临时数据库。
