# 桌面主应用源集

kotlin/com/kaixu/desktop 放 main()、桌面根界面、表现层和系统集成。以后类路径图片或配置可放 resources；不要把机器上的绝对资源路径写进代码。

主类与 MSI/EXE 安装包配置放 desktopApp/build.gradle.kts。平台驱动若服务共享库可放 shared/jvmMain；窗口、菜单、托盘和快捷键属于本应用。
