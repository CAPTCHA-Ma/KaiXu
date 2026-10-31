# 页面状态

按业务放 `tasks/TaskEditorUiState.kt` 等不可变 data class。状态表示“此刻应显示什么”，例如草稿、列表、加载状态、保存状态、字段错误、版本冲突比较信息。

ViewModel 私有持有 `MutableStateFlow`，页面只读 `StateFlow`。修改用 `copy()` 产生新状态，不在 Screen 中改内部列表。

UiState 不作为 HTTP 请求或数据库实体。焦点、菜单展开、滚动位置等局部视觉状态可放 Composable 的 `remember` 或适当的保存机制；影响提交与失败重试的草稿属于 ViewModel。

## 适宜存放的文件

可复用的 XxxUiState.kt、同一交互流程的状态枚举与结果显示模型可以放这里。侧栏宽度、窗口状态、底部导航选择等端专属状态放各端；不把所有端的字段塞进一个巨型状态类。
