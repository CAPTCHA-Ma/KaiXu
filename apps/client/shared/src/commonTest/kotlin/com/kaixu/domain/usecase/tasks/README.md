# 任务业务动作测试

CreateTaskUseCaseTest.kt 覆盖绕过页面直接调用时的标题规则与规范化。适宜新增完成、删除等业务动作的边界测试；复杂事务应另有后端验证，客户端替身不能证明真实事务原子性。

从客户端工程根目录运行 `./gradlew -PdesktopOnly=true :shared:jvmTest`。测试类采用 XxxTest.kt 命名，新增测试替身放本测试源集。
