# Kotlin Windows 与 Android 客户端

在 Android Studio 或 IntelliJ IDEA 打开本目录。项目按“各端独立 UI，共享业务与数据，按需共享 ViewModel”组织。先看 [分层开发指南](../../docs/03-design/client-mvvm-guide.md)，再按各目录 README 查适宜存放的文件。

当前桌面任务已使用 Room 2.8.4 持久化，重启可读取；Android 仍使用内存示例。
数据库定义、DAO、Entity 和迁移说明见 [本地数据源](shared/src/commonMain/kotlin/com/kaixu/data/datasource/local/README.md)。
数据库版本 2 已包含任务、固定事件和个人工作时段表及基础 DAO，页面仍只使用任务标题；尚未实现账号分库与同步；文中较早的内存演示步骤仅适用于 Android 或测试替身。

## 三个模块的边界

| 模块 | 适宜存放的文件 |
| --- | --- |
| [androidApp](androidApp/README.md) | MainActivity、AndroidApp、移动端页面、导航、主题、权限与安装配置 |
| [desktopApp](desktopApp/README.md) | main()、DesktopApp、桌面页面、导航、主题、窗口与安装包 |
| [shared](shared/README.md) | 业务对象、校验、UseCase、仓储、数据源、公共技术能力，可复用的 ViewModel 与 UiState |

依赖为 androidApp → shared ← desktopApp。两个入口分别进入 AndroidApp 与 DesktopApp，各自维护页面和返回栈。当前保留相同的标题录入示例，后续可分别设计完整布局和操作范围，尚未实现全部 SRS 页面。

SRS FR-X02 与表 13 规定 Windows 提供详细规划和管理，Android 提供查看、快捷录入、状态更新与简单改期。布局不同可以继续复用 ViewModel；字段、事件和流程不同时在本端实现 ViewModel，共同调用共享 UseCase。

## 从文件职责找位置

两个 App 的源码根为 androidApp/src/main/kotlin/com/kaixu/android 与 desktopApp/src/main/kotlin/com/kaixu/desktop，结构对应：

```text
app/                         本端 App 与宿主依赖容器
presentation/
├── ui/tasks/                本端任务 Screen、Route
├── ui/settings/             本端设置说明页
├── navigation/              本端 NavHost、目的地、页面 VM 作用域
├── theme/                   本端颜色、字体、主题入口
├── components/              本端跨页面组件，预留
├── viewmodel/               本端专属交互逻辑，预留
└── state/                   本端专属页面状态，预留
```

共享源码根为 shared/src/commonMain/kotlin/com/kaixu：

```text
presentation/
├── state/tasks/             共享 TaskEditorUiState
└── viewmodel/tasks/         共享 TaskEditorViewModel
domain/
├── model/tasks/             Task 业务对象
├── validation/tasks/        TaskTitle 纯规则
├── repository/              TaskRepository 接口
└── usecase/tasks/            创建、订阅、刷新动作
data/
├── repository/              DefaultTaskRepository
├── datasource/              接口、memory、remote/local 预留
├── dto/tasks/               HTTP 协议数据，预留
└── mapper/                  数据转换，预留
app/di/                     AppGraph 公共装配
core/                       网络、会话、平台接口，预留
```

调用链：各端 Screen / Route → 共享或本端 ViewModel → 共享 UseCase → Repository → DataSource。返回链：已确认数据 → ViewModel 的只读 UiState → 各端 Screen。

Task、UiState、未来的 HTTP DTO 与缓存 Entity 职责不同。页面使用 @Composable Kotlin 函数。Android 图标、字符串与图片可放 src/main/res，桌面类路径资源可放 src/main/resources；当前页面文字仍在 Kotlin 中，没有独立图片资源。

## shared 中的平台源集

shared 是多平台库，commonMain 才是跨平台公共源码。Android 选择 commonMain + androidMain，桌面选择 commonMain + jvmMain；jvmMain 已包含桌面数据库创建适配。jvmMain 对应当前 jvm() 目标，不自动供 Android 使用，也不专指 Windows。

库需要的平台网络引擎、缓存驱动、安全存储等可放相应平台源集。需要宿主配合的权限回调、窗口、托盘等放 App，也可以在 App 实现公共接口再注入。通过接口或必要的 expect/actual 连接，shared 不反向依赖 App。

shared 已移除 Compose UI 与导航插件和依赖；保留 Lifecycle ViewModel 核心与协程。共享源码不等于共享实例，两个进程的内存独立，跨设备同步尚未实现。

## 开发环境与运行

使用 JDK 21。Android 另需 SDK Platform 36 和所需 Build Tools。在 IDE 设置 Gradle JDK；终端为本机配置 JAVA_HOME。Android SDK 用 ANDROID_HOME 或本目录 local.properties 指定，勿提交本机路径。

Windows 使用 `gradlew.bat`，macOS/Linux 使用 `./gradlew`。首次下载构建工具与依赖需要网络。先用桌面模式不需要 Android SDK：

```bash
# 桌面启动
./gradlew -PdesktopOnly=true :desktopApp:run

# 共用规则与 ViewModel 测试
./gradlew -PdesktopOnly=true :shared:jvmTest

# 无图形环境下检查桌面编译
./gradlew -PdesktopOnly=true :desktopApp:classes

# Android 调试 APK
./gradlew :androidApp:assembleDebug

# 连接手机或模拟器后安装
./gradlew :androidApp:installDebug

# 在 Windows 主机制作安装包
# gradlew.bat -PdesktopOnly=true :desktopApp:packageMsi
```

Android APK 输出于 `androidApp/build/outputs/apk/debug/`；桌面安装包输出于 `desktopApp/build/compose/binaries/`，需在 Windows 构建并验收 Windows 包。

构建版本固定在 `gradle/libs.versions.toml` 和 Wrapper：Kotlin 2.2.20、Compose 1.9.3、AGP 8.11.1、Gradle 8.13、Lifecycle 2.9.5、Coroutines 1.10.2、Navigation Compose 2.9.1。当前 AGP 8.x 配置不能未经迁移直接换成 AGP 9 DSL。

## 自己走一次真实调用链

1. 空标题保存：显示校验错误，列表不变。
2. 输入“整理需求访谈”，新增任务：保存期间显示进行中，成功后增加任务并清空草稿。
3. 点击“模拟下一次保存失败”，输入新标题后保存：错误可见，草稿保留，列表不增加。
4. 再次新增：成功创建一次。
5. 保存时取消：当前内存实现不会写入，草稿保留。真实 HTTP 取消能否确定提交状态必须另行处理。

关闭应用会丢失数据，两端内存互不共享。正式 HTTP 接入的 DTO、mapper、错误类型、权限、版本和幂等步骤见整体指南第7节；相关目录目前只有说明文档。

## 页面导航与验证

启动进入任务页，点击“打开设置”，再点击“返回任务”。各端 AppContainer 在宿主范围保留 AppGraph，各端 Navigation 在任务条目范围创建共享 TaskEditorViewModel。往返后草稿、错误与已创建任务仍在；说明页没有实际设置开关。

详见 [导航设计](../../docs/03-design/client-navigation.md)、[手工验收步骤](../../tests/acceptance/new-task-demo.md) 和 [双端拆分验证](../../docs/04-testing/client-platform-split-verification.md)。

桌面导航回归运行 ./gradlew -PdesktopOnly=true :desktopApp:test，需要图形环境；Linux 无显示会话可使用 xvfb-run -a。公共逻辑测试不需要图形环境。Android 真机返回、旋转和 Windows 安装行为需另行验收。
