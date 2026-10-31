# 新增任务示例验证记录

日期：2026年10月29日。此记录只覆盖当前MVVM教学示例，不等同于完整SRS验收。

## 构建与自动测试

实际环境为Linux x86_64、JDK 21、Gradle 8.13。客户端源码固定Kotlin 2.2.20、Compose Multiplatform 1.9.3、AGP 8.11.1。

已执行：

```bash
# Android、共用JVM测试和桌面编译
gradle --no-daemon :androidApp:assembleDebug :shared:jvmTest :desktopApp:classes

# 另外通过工程自带Wrapper验证桌面单独构建入口
./gradlew --no-daemon -PdesktopOnly=true :shared:jvmTest :desktopApp:classes
```

执行时配置了JAVA_HOME、ANDROID_HOME与临时GRADLE_USER_HOME，绝对本机路径没有写进项目构建配置。两次构建均成功。首次官方发行包下载较慢，验证环境从镜像下载后核对SHA-256，工程Wrapper仍使用官方URL并固定校验值。

`TaskEditorViewModelTest`：**8项通过，0失败，0错误**。

| 测试行为 | 结果 |
| --- | --- |
| 空标题拒绝创建并保留输入 | 通过 |
| 标题去除两端空白，确认成功后清空草稿 | 通过 |
| 模拟失败不写入，保留草稿，重试只创建一条 | 通过 |
| 保存期间连续点击不重复提交 | 通过 |
| 取消等待中的内存保存不写入 | 通过 |
| 121字符标题拒绝 | 通过 |
| 首次调度前立即取消不会卡在保存中，之后可重试 | 通过 |
| 恰好120字符标题接受 | 通过 |

本机JUnit报告位于 `apps/client/shared/build/reports/tests/jvmTest/index.html`，XML位于 `shared/build/test-results/jvmTest/`。这些是自动生成的本机文件，不提交版本库。

## 实际界面检查

在独立虚拟显示环境实际运行JVM桌面应用，执行空标题保存、正常创建、触发模拟失败和再次保存。检查截图确认：错误提示可见，失败草稿仍在，失败时任务数不变，重试后任务数由1变2。首次图形加速不可用时运行环境自动回退，不影响检查操作。

截图见 [screenshots/](screenshots/)，是当前应用实际运行结果。桌面截图显示同一份共享Compose界面，不是Android真机截图。

## 构建产物与未验证范围

- 已生成Android调试APK：`apps/client/androidApp/build/outputs/apk/debug/androidApp-debug.apk`。
- 已完成JVM桌面源码编译与Linux图形环境运行检查。
- 当前没有已连接Android设备或运行中的模拟器；尚未执行Android安装、旋转、键盘及真机界面验收。
- 未在Windows主机生成或安装MSI/EXE，不能把Linux桌面运行记作Windows实机验收。
- 未实现或验证真实后端、持久化、账号权限、跨端同步、HTTP幂等和离线缓存。

模拟失败发生在内存写入前，因此可以直接重试。未来真实网络可能出现“服务器成功但响应丢失”，届时必须增加服务端幂等与结果查询。

