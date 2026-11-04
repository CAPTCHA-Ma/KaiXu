# 共享库的桌面 JVM平台实现

本源集保存“共享库需要、实现依赖 JVM API”的代码。DesktopDatabaseFactory 打开持久用户目录或显式路径的 Room 数据库，复用公共驱动与迁移配置。

适宜在 `kotlin/com/kaixu/` 对应业务包中存放平台网络引擎工厂、个人缓存驱动、安全凭据存储等实现。通用接口留在 commonMain，例如 core/platform/CredentialStore.kt；实现可命名为 DesktopCredentialStore.kt。数据适配可以放对应 data 包，不必全部堆进 core/platform。

当前目标构建组合是 `commonMain + jvmMain`。commonMain 不能直接导入本源集的具体类；通过公共接口注入或 expect/actual 连接。依赖配置写在 shared/build.gradle.kts 的对应 source set，不写入另一端。

main()、Window、托盘、菜单、快捷键和桌面页面属于 desktopApp。需要宿主配合的平台能力，也可以直接在该 App 实现公共接口并注入，无需强制放本目录。

jvmMain 来自当前 jvm() 目标；不自动供 Android 使用，也不等于 Windows 专属目标。Windows 专有集成需要明确系统边界，并在 Windows 上验证。
