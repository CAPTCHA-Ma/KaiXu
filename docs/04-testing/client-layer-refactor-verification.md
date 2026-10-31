# 客户端分层重构验证

> 本记录描述当时的共享页面或目录结构。当前已拆为 Android 与桌面独立页面及导航；最新路径与检查见 [双端拆分验证](client-platform-split-verification.md)。

验证日期：2026年10月30日。此次检查覆盖目录迁移、UseCase 接入、共享状态行为及两端构建，不代表完整 SRS 功能验收。

## 变更范围

将原 `features/tasks` 拆到 presentation、domain 和 data，将 AppGraph 移到 app/di；更新包名、import、装配与测试。CreateTaskUseCase 承接标题规则校验和规范化；ViewModel 调用创建、观察、刷新动作。页面与内存演示操作保持原有行为。

新增各目录职责说明与整体开发指南，remote、local、DTO、mapper、网络和会话目录仅预留 README。未新增运行依赖，未改变固定构建版本。

## 构建与测试

验证环境：Linux x86_64、JDK 21、现有 Android SDK Platform 36、项目自带 Gradle Wrapper。使用已有工具缓存，设置本机 JAVA_HOME、ANDROID_HOME、GRADLE_USER_HOME，路径不写入构建文件。

已成功执行：

```bash
./gradlew --offline --no-daemon -PdesktopOnly=true :shared:jvmTest :desktopApp:classes
./gradlew --offline --no-daemon :androidApp:assembleDebug

# 最终联合复查
./gradlew --offline --no-daemon :shared:jvmTest :desktopApp:classes :androidApp:assembleDebug
```

本机已有缓存时可用 `--offline`；首次构建按客户端 README 正常联网下载。

| 检查 | 结果 |
| --- | --- |
| TaskEditorViewModelTest | 8项通过，0失败，0错误 |
| CreateTaskUseCaseTest | 2项通过，0失败，0错误 |
| JVM 桌面代码编译 | 通过 |
| Android 调试 APK 构建 | 通过 |
| Kotlin 包名与目录对应 | 通过 |
| 生产代码层依赖约定 | 通过静态检查 |
| Project 内 Markdown 本地链接 | 通过 |
| 旧 features 源码或 import 残留 | 无；旧路径只在迁移对照表保留 |

ViewModel 测试覆盖空标题、成功规范化与清草稿、失败保留并重试、防连点、取消、长度边界和立即取消后的恢复。新增 UseCase 测试验证绕过 ViewModel 时仍拒绝无效输入、规范化有效标题，避免新层仅成为未经验证的转发位置。

JUnit报告位于 `apps/client/shared/build/reports/tests/jvmTest/index.html`；APK位于 `apps/client/androidApp/build/outputs/apk/debug/androidApp-debug.apk`。这些是本机构建产物，不作为源代码提交。

## 验证边界

本次没有重新执行桌面图形操作、Android 真机交互或 Windows 安装测试。原示例的界面截图与历史记录保留在 [demo-verification.md](demo-verification.md)，不能把历史结果当作本次实机验收。

正式网络、持久化、账号与权限、版本冲突、幂等、双端刷新、个人离线缓存尚未实现；新增 README 与设计示例不是相应功能通过验收的证据。
