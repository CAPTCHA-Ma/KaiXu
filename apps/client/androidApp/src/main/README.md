# Android 主应用源集

AndroidManifest.xml 描述 Activity、权限和应用元信息；kotlin/com/kaixu/android 放启动代码、根界面和本端表现层。以后图标、strings.xml、图片等 Android 资源放 res，必要的原始资源放 assets。

Compose 页面使用 Kotlin 函数，当前不要求 XML 布局。不要将本机 SDK 路径或签名密钥放入源集；SDK 路径用本机 local.properties，应用配置放模块 build.gradle.kts。
