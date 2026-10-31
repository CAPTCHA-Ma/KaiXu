# Android 源码根目录

MainActivity.kt 是 Android 系统启动入口，只处理宿主生命周期并调用 AndroidApp。

- `app/`：本端根界面与宿主依赖容器。
- `presentation/`：本端页面、导航、主题、组件与本端交互逻辑。

适宜新增本端启动接线和宿主服务适配代码。业务实体、时间规则、UseCase 和数据访问继续在 shared；平台适配变多后可以增加有明确职责的 integration 包，避免把所有实现堆进入口文件。

包名以 com.kaixu.android 开头。共享类继续导入 com.kaixu.domain、com.kaixu.presentation.viewmodel 等包，不能改为依赖另一端 App。
