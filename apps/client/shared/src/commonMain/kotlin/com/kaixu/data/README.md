# 数据层

这里落实数据能力，属于 MVVM 的 Model 范围。UseCase 只看到仓储接口，不需要知道这些实现细节。

- `repository/`：实现 domain/repository，协调数据源并发布已确认业务数据。
- `datasource/`：声明任务、固定事件和工作时段的访问接口；local 为 Room 实现，remote 为远程访问预留。
- `dto/`：未来 HTTP 请求和响应的数据结构。
- `mapper/`：当前 Entity 与领域对象的转换，以及未来 HTTP DTO 的转换。

Repository 决定何时读取、何时更新数据状态；DataSource 执行具体访问。这里不修改 UiState、不调用 UI 回调。两端均已接入本地 Room，真实网络与同步尚未接入。

## 新增文件的顺序

先确认 domain 的仓储契约，再添加数据源实现和必要的 DTO、mapper，最后由 app/di 装配。TaskRepository 接口仍在 domain/repository；不要在这里再创建相同接口或放页面 ViewModel。
