# 桌面页面交互测试

AppNavigationTest.kt 使用真实 DesktopApp、DesktopNavigation 和任务页面，检查草稿、保存结果、失败提示在设置往返后的保留，以及显式重试。测试宿主提供 LifecycleOwner 和 ViewModelStoreOwner。

适宜增加本端导航、页面和系统接线的回归测试；共享规则测试放 shared/commonTest。执行客户端根目录的 `./gradlew -PdesktopOnly=true :desktopApp:test`，需要图形环境，Linux 可用 xvfb-run -a。

测试当前通过 JVM 界面验证交互，不等于 Windows 安装、托盘和快捷键已验收，也不覆盖 Android 真机行为。
