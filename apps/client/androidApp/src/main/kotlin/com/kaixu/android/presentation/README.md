# Android 表现层

本端负责“页面怎样组织、用户怎样操作”。适宜放 Compose 界面函数、导航、视觉样式，以及只适用于本端的页面状态和事件处理。

| 子目录 | 适宜存放的文件 |
| --- | --- |
| `ui/` | XxxScreen.kt、订阅状态并绑定回调的 XxxRoute.kt |
| `navigation/` | AndroidNavigation.kt、AndroidDestination.kt、导航参数和返回栈接线 |
| `components/` | 多个本端页面确实复用的按钮、卡片、加载与错误组件 |
| `theme/` | KaiXuTheme.kt、颜色、字体和形状定义 |
| `viewmodel/` | 本端独有交互流程的 XxxViewModel.kt，当前预留 |
| `state/` | 与上述本端流程配套的 XxxUiState.kt，当前预留 |

当前 TaskEditorViewModel 与 TaskEditorUiState 仍从 shared 导入，因为两端标题录入流程相同。以后本端页面增加不同事件时，在本端扩展交互逻辑，公共规则继续调用 shared/domain；不复制仓储和校验实现。

Screen 通过状态和回调工作，Route 连接 ViewModel，navigation 负责跳转。HTTP、SQL 和服务端鉴权不属于这些目录。
