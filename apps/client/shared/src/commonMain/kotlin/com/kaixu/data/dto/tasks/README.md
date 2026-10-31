# 任务 DTO 预留

未来任务接口确认后，在这里定义新建请求、更新请求与任务响应。示意：

```kotlin
// 仅展示命名与职责；字段不构成已确认 API。
data class CreateTaskRequestDto(val title: String)
```

响应可能需要服务端 ID、版本、截止与工作区等字段，以 OpenAPI 为准。`isSaving`、`draftTitle`、错误提示不属于 TaskResponseDto。当前没有 DTO 源码或可用任务 HTTP 接口。

## 不应存放的文件

TaskEditorUiState、TaskEditorViewModel、界面组件和内存数据源不放这里。没有确认契约前只保留 README，不能把命名示例写成已交付网络接口。
