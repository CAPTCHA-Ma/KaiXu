# 任务业务对象

`Task.kt` 表示已确认创建的任务，当前只有 `id` 与 `title`。它是教学子集，不代表 FR-T01 的完整字段。

后续维护任务状态枚举、截止日期、工作区归属和来源关系时放在这个业务包。保存按钮、输入草稿和加载提示属于 presentation/state；服务端 JSON 名称属于 data/dto。

不要把私有 WorkSession 列表嵌进共享 Task 返回给其他成员。正式 ID 格式与版本字段以未来 API 契约为准，当前本地任务使用 UUID 字符串。

## 文件归属示例

Task.kt、未来的 TaskStatus.kt、任务业务输入值对象适合放这里。TaskResponseDto.kt 放 data/dto/tasks；TaskEditorUiState.kt 放 presentation/state/tasks；TaskEditorScreen.kt 放对应 App。
