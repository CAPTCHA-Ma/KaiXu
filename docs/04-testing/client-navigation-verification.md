# 客户端导航验证记录

> 本记录描述当时的共享页面或目录结构。当前已拆为 Android 与桌面独立页面及导航；最新路径与检查见 [双端拆分验证](client-platform-split-verification.md)。

日期：2026年10月30日。新增 Navigation Compose 2.9.1，任务编辑和设置说明两个目的地，编辑 ViewModel 归导航条目管理，AppSessionViewModel 继续保存应用级依赖。

## 构建

在 Linux、JDK21 与现有 Android SDK 环境执行成功：

```bash
./gradlew --no-daemon :shared:jvmTest :desktopApp:classes :androidApp:assembleDebug
```

保留 Kotlin 2.2.20、Compose 1.9.3 和 Lifecycle 2.9.5 等已有显式版本。Android 调试包已生成于 apps/client/androidApp/build/outputs/apk/debug/androidApp-debug.apk。

## 实际导航界面测试

在独立 Xvfb 图形会话中执行成功：

```bash
./gradlew --offline --no-daemon -PdesktopOnly=true :desktopApp:test
```

本机以 DISPLAY 指向已有的独立测试显示；普通 Linux 环境可通过 `xvfb-run -a` 启动。offline 仅适用于依赖已缓存的环境，首次运行需要联网。

| 测试 | 结果 |
| --- | --- |
| 共用 TaskEditorViewModelTest | 8通过，0失败 |
| 共用 CreateTaskUseCaseTest | 2通过，0失败 |
| 导航：未保存草稿往返后保留，保存后列表往返仍存在 | 通过 |
| 导航：失败提示与草稿往返后保留，重试只创建一条 | 通过 |
| JVM桌面编译 | 通过 |
| Android调试APK构建 | 通过 |

UI测试使用实际 App、NavHost、页面与仓储，提供真实窗口通常提供的 LifecycleOwner 和 ViewModelStoreOwner。导航按钮采用在 UI 线程执行的语义点击；测试结束先移除界面，再清理测试宿主，避免颠倒生命周期清理顺序。

界面测试报告位于 desktopApp/build/reports/tests/test/index.html，共用测试报告仍位于 shared/build/reports/tests/jvmTest/index.html。CI已增加 desktopApp:test 和对应报告收集，本次未运行远程CI。

## 范围

本次验证了共享导航在JVM测试界面的行为，没有进行Android真机返回键、旋转与退出测试，也没有在Windows主机验证安装或系统集成。桌面没有新增返回快捷键，用户通过设置页内的返回按钮返回。

设置页只说明演示范围；账号、云端同步、持久化和应用重启恢复仍未实现。页面往返保留输入不等于跨进程保存。
