# Desktop 跨页面组件

适宜放多个本端页面真正复用的 UI 函数，例如 LoadingIndicator.kt、ErrorMessage.kt、WorkspaceBadge.kt。接口使用展示数据、Modifier 与事件回调，避免组件自己获取全局仓储。

现有窗口框架组件：`DesktopShell` 固定标题栏与内容布局，`DesktopTitleBar` 提供设置及历史按钮，`DesktopSidebar` 提供窄版主导航；两栏通过 `DesktopChromeButton` 共用按钮外观与交互反馈，色板和尺寸由 theme 中的 `DesktopChromeColors` 提供。

`DesktopTitleCaption` 在标题栏中心显示页面名称，处理悬停预览、退出复原及导航确认的连续动画。预览回调传递 `DesktopDestination`，标题统一读取目的地的 `title`。

导航入口优先使用 `DesktopNavigationButton(destination, onNavigate, onPreview, ...)`：组件以同一个目标进行悬停预览和点击跳转，按钮文案可以与页面名称不同，不接受单独的预览文字。普通操作按钮使用 `DesktopChromeButton` 时默认不预览；返回/前进由历史提供 `previewDestination`。不要在按钮里重复填写目标页面名称。

只有任务页使用的卡片先留在 ui/tasks；只有两端视觉与行为都适合复用时，才评估进一步提取跨端 UI。

本目录不放完整页面、导航图、HTTP 请求或业务校验。颜色与字号来源放 theme，数据保存交给 ViewModel 和 UseCase。
