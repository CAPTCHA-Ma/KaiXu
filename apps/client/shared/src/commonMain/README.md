# 跨平台公共源集

生产源码放 `kotlin/com/kaixu/`，继续按 app、presentation、domain、data、core 的职责分包；各包 README 说明文件归属。适合存放两端都能编译、意义一致的 Kotlin 实现与接口。

Android 和桌面目标都会编译这里的代码。不能直接引用 Android Context、桌面窗口等平台类型；平台实现放对应源集或由 App 通过公共接口注入。

本次拆分后这里没有 UI 资源或 Compose 页面。应用图片、字符串、字体由各端资源机制管理；不要在本目录新增一套 Android 页面与桌面页面分类。
