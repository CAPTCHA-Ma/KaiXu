# 远程数据源 预留

后续写 `HttpTaskDataSource.kt` 等 API 适配器：通过注入的 HTTP 客户端发请求、解析 DTO、使用 mapper 返回业务对象。请求地址、认证头与传输错误在这里或 core/network 处理。

本目录与后端协作，约定请求字段、响应、错误码、版本与幂等键；正式依据放 Project/contracts。当前没有真实 API，也未添加 Ktor 依赖，不应复制一个虚构 URL 作为已实现接口。

权限、冲突等结果映射成上层可处理的错误类型；保留 CancellationException。写请求遇到响应丢失时需查询原操作结果或使用同一幂等键显式重试，不自动生成新操作。

## 适宜存放的文件

按业务可放 HttpTaskDataSource.kt、HttpWorkspaceDataSource.kt；共用超时与认证配置放 core/network，JSON 类型放 data/dto，转换放 mapper。网络引擎的具体创建属于平台适配。
