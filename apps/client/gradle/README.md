# 构建版本与启动工具

libs.versions.toml 集中声明 Kotlin、Compose、Lifecycle、协程与导航版本及插件别名。新增依赖优先在此命名，再在真正使用它的模块声明；UI 与导航依赖属于各 App，shared 仅使用 ViewModel 核心与协程等公共能力。

wrapper/ 保存 Gradle Wrapper 的 properties 与 jar，配合客户端根目录 gradlew、gradlew.bat 启动构建。升级 Wrapper 时同步发行地址和校验值，不存放业务源码、本机 JDK 或下载缓存。

各模块的 build.gradle.kts 声明本模块目标、依赖和打包；根 settings.gradle.kts 声明模块及仓库。不要在这些文件中硬编码个人 SDK、JDK 或代理路径。
