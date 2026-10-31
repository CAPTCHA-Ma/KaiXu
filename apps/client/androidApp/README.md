# Android 客户端

本模块拥有独立界面、导航、主题和宿主生命周期，依赖 shared 提供业务与数据能力。当前仍是任务标题录入与设置说明的最小示例；页面初始内容保留原行为，后续可独立演进，不代表已实现完整 SRS。

SRS 5.7 节表 13 对本端的主要规划为：今日和近期查看、快速录入、状态更新与简单改期。共享固定事件在移动端只读。

| 目录或文件 | 适宜存放的内容 |
| --- | --- |
| `src/main/kotlin/com/kaixu/android/` | 本端启动与应用代码，包名和目录对应 |
| `app/`（上述源码根内） | AndroidApp 根界面、AndroidAppContainer 宿主依赖容器 |
| `presentation/`（上述源码根内） | 页面、Route、导航、组件、主题及本端特有状态和 ViewModel |
| `build.gradle.kts` | 本端依赖、编译与应用 ID、SDK、签名等配置 |

启动关系是 MainActivity → AndroidApp → AndroidNavigation。导航为任务条目创建共享 TaskEditorViewModel，ViewModel 调用 shared 的 UseCase。不要在 Activity、Window 或 Screen 中直接发业务请求。

本端页面可以与桌面完全不同；只有交互流程相同才共用 ViewModel。不要复制 shared/domain 的标题、日期、权限规则或 shared/data 的仓储实现。需要宿主参与的平台能力可在这里实现公共接口并注入；纯库级平台驱动也可放 shared 的平台源集。

Manifest、应用资源、安装权限与 Activity 接入放本模块。Android 图标和 strings.xml 可放 src/main/res，当前页面文字仍在 Kotlin 中。构建命令：在客户端根目录运行 ./gradlew :androidApp:assembleDebug。真机返回键、旋转和进程恢复需要另外验收。

详见 [客户端总览](../README.md) 和 [本端源码说明](src/main/kotlin/com/kaixu/android/README.md)。
