# 数据层

这里落实数据能力，属于 MVVM 的 Model 范围。UseCase 只看到仓储接口，不需要知道这些实现细节。

- `repository/`：实现 domain/repository，协调数据源并发布已确认业务数据。
- `datasource/`：定义和实现实际读取、写入能力，内存、远程、本地分开。
- `dto/`：未来 HTTP 请求和响应的数据结构。
- `mapper/`：未来 DTO、缓存记录与业务模型之间的转换。

Repository 决定何时读取、何时更新数据状态；DataSource 执行具体访问。这里不修改 UiState、不调用 UI 回调。当前只有内存实现；真实网络和本地持久化尚未接入。

## 新增文件的顺序

先确认 domain 的仓储契约，再添加数据源实现和必要的 DTO、mapper，最后由 app/di 装配。TaskRepository 接口仍在 domain/repository；不要在这里再创建相同接口或放页面 ViewModel。
