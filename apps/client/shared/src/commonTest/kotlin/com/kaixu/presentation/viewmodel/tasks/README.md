# 共享任务 ViewModel 测试

TaskEditorViewModelTest.kt 覆盖草稿、保存、失败保留、显式重试、取消与重复点击等行为。适宜放共享任务交互流程的逻辑测试，不引入 Compose 页面和图形环境；本端独有交互测试留在对应 App。

从客户端工程根目录运行 `./gradlew -PdesktopOnly=true :shared:jvmTest`。测试类采用 XxxTest.kt 命名，新增测试替身放本测试源集。
