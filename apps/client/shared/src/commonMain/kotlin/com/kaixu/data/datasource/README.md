# 数据源接口与实现

`TaskDataSource.kt` 声明当前任务数据源的列表和创建能力。仓储调用此接口，具体实现放 `memory/`，未来远程和本地实现放 `remote/`、`local/`。

当前接口返回业务 Task，便于在内存和未来 HTTP 适配器之间替换。HTTP 适配器内部先解析 DTO、调用 mapper，再返回 Task，不把 JSON 类型传给 ViewModel。

后续有同时访问远程和缓存的需求时，可分别定义 TaskRemoteDataSource 与 TaskLocalDataSource，由仓储协调；不要强迫所有来源具备完全相同的能力。

## 文件组织

本层根目录放 TaskDataSource.kt 等能力接口，具体访问放 memory、remote、local 子包。平台依赖的驱动实现可在 androidMain 或 jvmMain 保持相同包层级，不能在 commonMain 直接调用平台文件 API。
