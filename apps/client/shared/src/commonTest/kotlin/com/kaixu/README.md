# 共用测试

测试目录对应生产层级，文件名使用 `XxxTest.kt`。

- `domain/usecase/tasks/TaskUseCasesTest.kt`：绕过 ViewModel 调用统一任务入口时仍校验、规范化输入。
- `presentation/viewmodel/tasks/TaskEditorViewModelTest.kt`：真实 UseCase 与测试 Repository 下的草稿、失败、重试、取消、连续点击与边界。

新增纯规则测试放 domain/validation；DTO 转换和数据策略测试放 data。未来网络契约测试使用可控服务或响应样本，不依赖外部生产系统。运行：客户端目录 `./gradlew -PdesktopOnly=true :shared:jvmTest`。

导航页面的实际交互测试放在 desktopApp/src/test，使用 Compose UI 测试工具，执行 desktopApp:test；它需要图形环境，不与这里的纯业务测试混为一个任务。

## 双端拆分后的测试边界

共享 TaskEditorViewModel 的逻辑只维护一套测试；各端 Screen、导航和主题在各端验收。测试替身和辅助函数按测试的业务包放置，不复制到生产代码。运行两个客户端时仍是独立实例，公共测试不能证明跨设备同步。
