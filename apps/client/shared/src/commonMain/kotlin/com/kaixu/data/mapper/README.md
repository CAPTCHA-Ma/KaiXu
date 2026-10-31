# 数据转换 预留

后续写 `TaskMapper.kt` 等纯函数或扩展函数，将远程 DTO 或本地 Entity 转为 domain model，将业务命令转为请求 DTO。

例如 `fun TaskResponseDto.toDomain(): Task`，转换时集中处理字段命名、状态枚举、缺省值、时间与日期精度。业务层不识别 JSON 的 snake_case。

这里不发网络、不操作 StateFlow、不吞掉无法识别的状态。SRS 的日期与时刻都按 Asia/Shanghai 解释和显示，日期型截止保留日期精度，不能擅自补成某个 UTC 午夜。

## 文件边界

可按业务增加 TaskMapper.kt、WorkspaceMapper.kt 或对应子包。保持转换可单独测试；格式化给用户看的日期文字属于表现层，不能把展示字符串当作标准业务时间保存。
