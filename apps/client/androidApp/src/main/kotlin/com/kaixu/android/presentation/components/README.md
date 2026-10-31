# Android 跨页面组件 预留

适宜放多个本端页面真正复用的 UI 函数，例如 LoadingIndicator.kt、ErrorMessage.kt、WorkspaceBadge.kt。接口使用展示数据、Modifier 与事件回调，避免组件自己获取全局仓储。

只有任务页使用的卡片先留在 ui/tasks；只有两端视觉与行为都适合复用时，才评估进一步提取跨端 UI。当前没有组件实现，不为填目录建立空类。

本目录不放完整页面、导航图、HTTP 请求或业务校验。颜色与字号来源放 theme，数据保存交给 ViewModel 和 UseCase。
