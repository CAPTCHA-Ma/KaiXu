# Desktop 页面与状态连接

按业务建立 tasks、settings、workspaces、scheduling 等子包，放 .kt 文件。XxxScreen.kt 使用 @Composable 接收状态和回调；XxxRoute.kt 订阅 ViewModel，把事件接到对应方法。

本目录拥有本端完整布局，可以独立于另一端修改宽屏、多栏、工具区和详细编辑流程。当前只有 tasks 与 settings 两个实际页面，后续业务名称不是已实现页面。

页面专用小组件先放该业务包；确有多个页面复用后再移到 components。Screen 不直接取得 AppGraph、Repository 实现或 HTTP 客户端；导航通过回调请求，不在业务层操作 NavController。
