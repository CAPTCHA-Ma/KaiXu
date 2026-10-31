# 公共依赖装配

`AppGraph.kt` 手工创建数据源 → Repository 实现 → UseCase。它向两端暴露相同业务能力，不创建页面、导航控制器或页面 ViewModel，也不决定布局。当前无需 DI 框架。

适宜存放 AppGraph.kt、未来的公共依赖工厂和账号范围依赖配置。若有平台差异，优先通过构造参数接收公共接口，由各端或平台源集提供实现；不要在这里导入 Activity 或 Window。

当前图固定使用 InMemoryTaskDataSource，`simulateNextSaveFailure()` 也是演示开关。未来接远程数据源时，需要调整装配入口并接收网络、会话等依赖；这不是已经实现的可切换生产配置。

同一个宿主容器持有一份 AppGraph，图中同一个仓储供页面使用。AndroidAppContainer、DesktopAppContainer 分别创建各自实例，两端不会共享同一内存对象。

页面 ViewModel 的创建和清理由各端 navigation 管理。退出账号时清理图、取消旧请求、隔离缓存是后续会话工作，不在普通页面重绘时执行。
