# 共享客户端库

本模块是 Kotlin Multiplatform 库，不单独启动。它向 Android 和桌面应用提供业务对象、规则、UseCase、数据访问，以及交互一致时可复用的 ViewModel 与 UiState。完整页面、导航、主题和宿主生命周期在两个应用模块中。

| 目录 | 适宜存放的文件 | 当前状态 |
| --- | --- | --- |
| `src/commonMain/` | 跨平台业务与数据代码、共享页面行为和接口 | 已有任务标题录入的公共调用链 |
| `src/commonTest/` | 公共规则、ViewModel、仓储等测试及测试替身 | 已有 UseCase 与 ViewModel 测试 |
| `src/androidMain/` | 共享库使用 Android API 的适配实现 | 仅预留说明 |
| `src/jvmMain/` | 共享库使用桌面 JVM API 的适配实现 | 仅预留说明 |

Android 构建选择 `commonMain + androidMain`；桌面构建选择 `commonMain + jvmMain`。`shared` 表示对外提供可复用能力的库，只有 `commonMain` 要求实现跨平台。`jvmMain` 对应当前的 `jvm()` 目标，并不自动供 Android 使用，也不专指 Windows。

平台能力可通过公共接口和构造函数注入，也可在必要时采用 expect/actual。库内部需要的网络引擎、缓存驱动等可以放平台源集；Activity 权限交互、窗口、托盘和具体页面留在 App。shared 不反向依赖 androidApp 或 desktopApp。

本模块不再使用 Compose UI 与导航插件或依赖；共享 ViewModel 只依赖 Lifecycle ViewModel 核心与协程。以后如确有跨端通用的 UI，可另行评估独立 UI 库，不要为了复用一个按钮把两端完整导航重新合并。

先读 [客户端总览](../README.md) 和 [分层开发指南](../../../docs/03-design/client-mvvm-guide.md)。共享代码不意味着共享运行中的对象；当前两个进程各自持有内存数据，没有云端同步与持久化。
