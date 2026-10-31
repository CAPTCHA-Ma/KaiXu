# 双端目录拆分验证

日期：2026 年 10 月 31 日。

本次将完整页面、Route、导航、主题及宿主依赖容器从共享库拆到 Android 和桌面应用。共享业务与数据代码、TaskEditorViewModel、TaskEditorUiState 保留一份；两端各自创建运行实例。

## 结构与依赖检查

| 检查项 | 结果 |
| --- | --- |
| Android 启动 | MainActivity → AndroidApp → AndroidNavigation |
| 桌面启动 | main() → DesktopApp → DesktopNavigation |
| 各端表现层 | 独立 ui、navigation、theme；components、专属 viewmodel/state 预留 |
| 共享层 | domain、data、core、app/di、可复用 ViewModel 与状态 |
| 宿主生命周期 | AndroidAppContainer、DesktopAppContainer 分别持有自己的 AppGraph |
| 页面生命周期 | 各端 task-editor 导航条目分别持有共享类型的 TaskEditorViewModel |
| Gradle 依赖 | shared 使用 Lifecycle ViewModel 核心和协程，移除 Compose UI、导航与相关插件；界面依赖由各 App 声明 |
| JVM 库产物 | 不含旧共享 App、AppSessionViewModel、ui 与 navigation 类 |

各职责目录 README 补充了适宜文件、调用关系、职责边界与当前实现状态。总览、分层指南、导航设计和示例入口已同步新路径；较早验证文档作为历史记录保留。

另检查了 72 份相关 Markdown 的本地链接，未发现失效目标；与迁移前备份对比，domain、data、core 的 Kotlin 源码没有变化。shared 的 jvmRuntimeClasspath 依赖报告确认不含 Compose UI 或导航。

## 编译与公共逻辑测试

在 Linux、JDK 21、现有 Android SDK Platform 36 和项目 Gradle Wrapper 环境中执行：

```bash
./gradlew --offline --no-daemon :shared:jvmTest :desktopApp:classes :androidApp:assembleDebug
```

结果：BUILD SUCCESSFUL。offline 仅因验证环境已有依赖缓存，首次构建需要联网。本机 JDK 与缓存路径通过环境变量配置，没有写入工程构建文件。没有升级 Kotlin、Compose、AGP、Lifecycle、协程或导航版本。

| 项目 | 结果 |
| --- | --- |
| CreateTaskUseCaseTest | 2 项通过，0 失败 |
| TaskEditorViewModelTest | 8 项通过，0 失败 |
| 桌面编译 | 通过 |
| Android 调试 APK | 构建通过 |

## 桌面导航回归

在独立 Xvfb 显示环境中运行：

```bash
./gradlew --offline --no-daemon -PdesktopOnly=true :desktopApp:test
```

结果：BUILD SUCCESSFUL，AppNavigationTest 两项通过，0 失败。测试入口已经改为实际 DesktopApp，覆盖：

- 未保存草稿在设置页往返后保留；保存后的列表和任务数量在往返后保留。
- 保存失败的提示与草稿在往返后保留；显式重试成功且只新增一条。

测试报告位于客户端的 shared/build/reports/tests/jvmTest/index.html 与 desktopApp/build/reports/tests/test/index.html；调试包位于 androidApp/build/outputs/apk/debug/androidApp-debug.apk。

## 验证边界

本次是目录和依赖重构，保留原有标题录入示例行为，没有新增完整 Windows 管理页面或 Android 今日页。两个界面初始布局相同，但页面源码、导航和主题已经独立，可分别演进。

Android 仅完成编译和调试包构建，没有验证真机系统返回、旋转或进程恢复；桌面 UI 测试运行于 Linux JVM，没有执行 Windows 安装、升级或系统集成验收，也没有运行远程 CI。

当前仍使用进程内数据源，无账号、真实 HTTP、持久化或双端同步。页面往返保留状态不等于应用重启恢复。
