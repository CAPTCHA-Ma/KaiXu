# 共享库的Android平台实现

本源集保存“共享库需要、实现依赖 Android API”的代码。当前只有说明文档，没有适配源码；无需为了填满目录创建空类。

适宜在 `kotlin/com/kaixu/` 对应业务包中存放平台网络引擎工厂、个人缓存驱动、安全凭据存储等实现。通用接口留在 commonMain，例如 core/platform/CredentialStore.kt；实现可命名为 AndroidCredentialStore.kt。数据适配可以放对应 data 包，不必全部堆进 core/platform。

当前目标构建组合是 `commonMain + androidMain`。commonMain 不能直接导入本源集的具体类；通过公共接口注入或 expect/actual 连接。依赖配置写在 shared/build.gradle.kts 的对应 source set，不写入另一端。

Activity、Manifest、权限申请、界面和 Android 返回行为属于 androidApp。需要宿主配合的平台能力，也可以直接在该 App 实现公共接口并注入，无需强制放本目录。

这里可调用 Android API；不复制公共任务、日期或权限规则。
