# FastAPI 服务端预留目录

当前新增任务示例使用客户端内存数据源，此处尚未实现服务或数据库。后续按下面职责增加 `.py` 与迁移文件，不将空目录视为已交付功能。

```text
src/kaixu_api/
├── core/                  配置、数据库会话、安全、日志、错误
├── modules/
│   ├── identity/          账号与本系统会话
│   ├── workspaces/        课程、项目和归档
│   ├── tasks/             任务、状态与完成流程
│   ├── scheduling/        固定事件和个人时段
│   ├── collaboration/     成员、邀请、所有权与权限
│   ├── resources/         文本、链接与关联
│   └── intake/            来源候选与确认
├── integrations/          GitHub、Canvas外部适配
└── jobs/                  后续异步检索任务
```

每个模块按需增加 `router.py`、`schemas.py`、`models.py`、`repository.py`、`service.py`、`policy.py`。依赖方向是路由→应用服务→权限/存储；应用服务管理事务，存储方法不能各自commit。后续在本目录新增 `pyproject.toml`、迁移配置和测试；当前不会自动部署空服务。

