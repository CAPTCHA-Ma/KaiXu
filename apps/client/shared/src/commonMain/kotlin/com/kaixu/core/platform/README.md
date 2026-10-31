# 平台能力接口 预留

后续写文件选择、打开外部链接、安全存储、网络状态等跨平台接口。接口和通用参数放 commonMain；Android 实现放 androidMain，桌面实现放 jvmMain。

优先构造函数注入，必要时使用 expect/actual。公共业务规则继续复用；完整页面与导航由各端实现，平台能力通过接口连接。

不要把 Activity、Window 传入 domain。当前没有具体平台接口源码。

## 接口与实现的职责

例如这里可定义 CredentialStore.kt、ExternalLinkOpener.kt 的公共接口，具体实现可放平台源集，也可由 App 注入。文件选择和权限请求若依赖 Activity 回调，宿主接线留在 androidApp；domain 不接收 Activity 或 Window。
