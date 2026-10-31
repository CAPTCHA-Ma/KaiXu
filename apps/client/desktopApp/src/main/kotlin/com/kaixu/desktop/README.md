# Desktop 源码根目录

Main.kt 的 main() 创建桌面窗口并调用 DesktopApp，处理窗口关闭与初始大小。

- `app/`：本端根界面与宿主依赖容器。
- `presentation/`：本端页面、导航、主题、组件与本端交互逻辑。

适宜新增本端启动接线和宿主服务适配代码。业务实体、时间规则、UseCase 和数据访问继续在 shared；平台适配变多后可以增加有明确职责的 integration 包，避免把所有实现堆进入口文件。

包名以 com.kaixu.desktop 开头。共享类继续导入 com.kaixu.domain、com.kaixu.presentation.viewmodel 等包，不能改为依赖另一端 App。
