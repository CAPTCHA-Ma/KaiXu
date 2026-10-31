# 共用基础能力 预留

只有确实跨多个业务复用的技术能力才放这里，避免变成任意 utils 的集合。

`network/` 放网络配置和通用错误映射；`session/` 放账号会话边界；`platform/` 放平台能力接口。任务规则和排程算法分别属于 domain/validation 与 domain/usecase。

当前这几个目录仅有职责说明，未接入网络、账号或平台适配。共享代码不能导入 android.*、java.awt.* 等平台专属 API。

## 适宜存放的文件

确有多个业务调用时再增加公共接口、配置或错误类型。只服务任务的工具放任务业务包；App 根界面、菜单、主题和导航不放 core。当前 network、session、platform 都是预留目录。
