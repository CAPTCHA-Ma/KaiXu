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
