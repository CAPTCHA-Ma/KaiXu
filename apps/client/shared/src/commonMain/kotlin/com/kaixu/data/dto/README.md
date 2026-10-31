# HTTP 请求与响应模型 预留

按业务建立 `tasks/` 等目录，写 `CreateTaskRequestDto.kt`、`TaskResponseDto.kt` 等与 API 契约一致的 data class。正式接入序列化工具时再添加注解和依赖。

DTO 表达线上的 JSON 字段，domain model 表达客户端业务含义，UiState 表达编辑交互。它们不是同一份类；没有 HTTP 的内存示例无需人为生成 DTO。

不要把整个 UiState 发给后端。正式请求字段、日期精度、ID类型、版本和错误结构必须先与 contracts 对齐。

## 文件组织

优先按 tasks、workspaces 等协议业务分包，再按 XxxRequestDto.kt、XxxResponseDto.kt 命名。协议字段变化集中在 DTO 和 mapper；不因桌面与手机布局不同复制两份相同协议类。
