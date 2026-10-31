# 跨平台公共测试源集

测试源码放 kotlin/com/kaixu/，目录与 commonMain 的职责对应，命名 XxxTest.kt。适合测试纯规则、UseCase、共享 ViewModel、数据映射和仓储策略；测试替身可放对应测试包。

使用可控协程与数据源，不依赖真实线上账号。当前有两项 UseCase 测试和八项 ViewModel 测试，运行 `./gradlew -PdesktopOnly=true :shared:jvmTest`。

桌面真实页面交互测试在 desktopApp/src/test；Android 设备交互测试应在 androidApp/src/androidTest。共享测试通过不等于两个客户端 UI 都已通过设备验收。
