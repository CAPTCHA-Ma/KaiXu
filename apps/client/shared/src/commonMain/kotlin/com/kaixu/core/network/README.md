# 网络公共设施 预留

后续写 HTTP 客户端工厂、环境基地址、超时、序列化配置、认证头和通用错误转换。业务 API 操作在 data/datasource/remote，不把所有 endpoint 堆在本目录。

提供401会话失效、403权限失效、版本冲突等可区分结果，具体状态码与结构以契约为准。日志不输出令牌和敏感原文；不自动重试不具备幂等保障的写操作。

当前未引入网络库。网络引擎有平台差异时，接口留在 commonMain，实现放 androidMain 或 jvmMain，再从装配处注入。

## 文件组织建议

未来可放 HttpClientFactory.kt、NetworkConfig.kt、通用传输错误类型；名称只是示意。平台引擎工厂放 androidMain 或 jvmMain，具体任务端点仍由 remote 数据源使用。
