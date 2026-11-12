# 任务业务动作测试

TaskUseCasesTest.kt 覆盖绕过页面直接调用统一任务入口时的完整输入校验与标题规范化。任务 CRUD、查询与订阅由 DefaultTaskRepositoryTest 和 TaskRepositoryDatabaseTest 覆盖。复杂事务应另有真实数据库验证，客户端替身不能证明事务原子性。

从客户端工程根目录运行 `./gradlew -PdesktopOnly=true :shared:jvmTest`。测试类采用 XxxTest.kt 命名，新增测试替身放本测试源集。
