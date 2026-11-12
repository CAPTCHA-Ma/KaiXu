# Desktop 客户端

本模块拥有独立界面、导航、主题和宿主生命周期，依赖 shared 提供业务与数据能力。当前仍是任务标题录入与设置说明的最小示例；页面初始内容保留原行为，后续可独立演进，不代表已实现完整 SRS。

SRS 5.7 节表 13 对本端的主要规划为：工作区组织、完整任务编辑、多个执行时段、全局排程和资源关联。

| 目录或文件 | 适宜存放的内容 |
| --- | --- |
| `src/main/kotlin/com/kaixu/desktop/` | 本端启动与应用代码，包名和目录对应 |
| `app/`（上述源码根内） | DesktopApp 根界面、DesktopAppContainer 宿主依赖容器 |
| `presentation/`（上述源码根内） | 页面、Route、导航、组件、主题及本端特有状态和 ViewModel |
| `build.gradle.kts` | 本端依赖、编译与主类、MSI/EXE 等打包配置 |

启动关系是 main() → DesktopApp → DesktopNavigation。导航为任务条目创建共享 TaskEditorViewModel，ViewModel 调用 shared 的 UseCase。不要在 Activity、Window 或 Screen 中直接发业务请求。

本端页面可以与Android完全不同；只有交互流程相同才共用 ViewModel。不要复制 shared/domain 的标题、日期、权限规则或 shared/data 的仓储实现。需要宿主参与的平台能力可在这里实现公共接口并注入；纯库级平台驱动也可放 shared 的平台源集。

菜单、窗口、托盘、快捷键和系统集成放本模块；JVM 桌面可在 Linux 开发，Windows 安装包及系统行为必须在 Windows 验证。构建命令：在客户端根目录运行 ./gradlew -PdesktopOnly=true :desktopApp:classes；启动使用 :desktopApp:run。

详见 [客户端总览](../README.md) 和 [本端源码说明](src/main/kotlin/com/kaixu/desktop/README.md)。

## 使用 JBR 21（不引入 Jewel）

桌面启动、桌面测试和打包支持使用指定的 JetBrains Runtime。当前只切换运行环境，未接入 JBR API、自定义标题栏或 Jewel，窗口外观保持现状。

1. 从 [JetBrains 官方发行页](https://github.com/JetBrains/JetBrainsRuntime/releases) 下载与本机系统、架构匹配的 **JBRSDK 21**，优先选择不附带 JCEF 的普通版本。Windows 常用 `windows-x64`；不要下载仅运行时的 `jbr` 或 `fastdebug` 包。
2. 解压到固定目录，确认根目录包含 `release`，且 `bin` 下包含 `java`、`javac`、`jlink`、`jpackage`（Windows 为 `.exe`）。Android Studio 自带 JBR 可能缺少打包工具，可用于启动验证，但完整打包应使用 JBRSDK。
3. 配置本机路径，二选一：用户级 Gradle 属性 `jbrHome`，或环境变量 `JBR_HOME`。属性优先于环境变量。路径必须指向 SDK 根目录，不能指向 `bin`；不要把本机路径写进仓库。

推荐在用户目录的 `.gradle/gradle.properties` 中加入以下一行（Windows 路径使用正斜杠，无需引号）：

```properties
jbrHome=C:/Dev/Java/jbrsdk-21
```

也可以在 PowerShell 中为当前终端设置（替换为实际解压路径）：

```powershell
$env:JBR_HOME = 'C:\Dev\Java\jbrsdk-21'
```

IDE 中将 Project SDK 和 Gradle JDK 选择为这个 JBRSDK 21，并重新同步 Gradle。设置环境变量时如果 IDE 已经运行，需要重启 IDE 才能读取新值；使用用户级 Gradle 属性则不依赖这一步。推荐通过 Gradle 的 `:desktopApp:run` 启动；直接运行 `Main.kt` 时，须另外确认运行配置的 JRE。

在客户端根目录执行（PowerShell）：

```powershell
.\gradlew.bat -PdesktopOnly=true :desktopApp:desktopRuntimeInfo
.\gradlew.bat -PdesktopOnly=true :desktopApp:run
```

第一条命令应显示预期的 `java.home`、`java.vendor = JetBrains s.r.o.` 和 Java 21 版本。`gradlew --version` 显示的是 Gradle 的 Java，不能替代这项检查。

如需验证打包运行时，在 Windows 上执行：

```powershell
.\gradlew.bat -PdesktopOnly=true :desktopApp:createDistributable
.\gradlew.bat -PdesktopOnly=true :desktopApp:runDistributable
.\gradlew.bat -PdesktopOnly=true :desktopApp:packageMsi
```

Compose 的 `application.javaHome` 同时控制桌面 `run` 和打包工具使用的 Java，桌面 `Test` 任务也使用同一个显式配置的 JBR。编译目标仍为 Java 21，共享层与 Android 没有新增 JBR 依赖。未提供上述配置时保留原 Java 选择方式，方便现有 CI 和普通 JVM 开发；这意味着尚未强制要求所有环境都使用 JBR。路径、厂商或主版本不符合要求时会给出配置错误。

Linux/macOS 使用 `./gradlew` 代替 `gradlew.bat`，且必须选择本机系统对应的 JBRSDK。Windows 安装包仍需在 Windows 上制作和验收。

本次验证（2026-11-12）：Linux 上显式选择现有 JetBrains JBR 21.0.10，`desktopRuntimeInfo` 确认厂商、版本与路径，`:desktopApp:classes` 和现有 `ResourceUrlTest` 通过。未执行 Windows 窗口交互及 MSI/EXE 打包验证；本机 Android Studio 附带 JBR 缺少 `jpackage`。
