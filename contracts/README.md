# 共同契约

`rules-fixtures/task-title.json` 保存当前新增任务示例的输入输出样本。Kotlin规则测试当前已独立覆盖同一边界，后续接入Python服务时，两侧应读取同一份JSON样本，避免规则漂移。

未来 `openapi.json` 从已实现FastAPI生成，再经评审提交；`examples/` 存放脱敏成功、失败、权限和冲突响应。当前不放置虚构的完整API契约。


客户端接入时的职责与确认项见 [联调约定清单](client-api-collaboration.md)。传输模型放客户端 `data/dto/`，转换放 `data/mapper/`，请求执行放 `data/datasource/remote/`；此处保存双方共同依据，不保存客户端页面状态。
