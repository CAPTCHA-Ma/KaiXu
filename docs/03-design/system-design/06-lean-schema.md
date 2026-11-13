# KaiXu 精简表结构（最终基线 v1.0）

> 范围：桌面 SQLite 本地库 + 服务端 PostgreSQL 权威库。  
> 基线：SRS Core + Priority + 用例分解中的任务阶段、番茄钟、离线同步；附件版本、重复事件、规则排程、提醒等 Extended 能力后置。  
> 结论：服务端 **18 张表**，桌面 SQLite **9 张表**。  
> 说明：本文是设计基线，不是可执行迁移脚本；正式 DDL/迁移在实现阶段由服务端迁移文件和 SQLDelight schema 落实。

## 1 设计约定

### 1.1 ID 与时间

| 项 | 约定 |
| --- | --- |
| 服务端主键 | UUIDv7，`uuid` 类型；应用生成，数据库 `gen_random_uuid()` 仅兜底 |
| 桌面主键 | `TEXT` 存 UUIDv7；不要用自增 `Long` 作为跨端主键 |
| 服务端时间 | `timestamptz`；业务时区固定 `Asia/Shanghai` |
| 桌面时间 | `INTEGER` 存 UTC epoch millis；仅日期字段用 `TEXT` `YYYY-MM-DD` |
| 日期精度 | `deadline_date` 与 `deadline_at` 至多一个；仅日期不补 `23:59` |
| 区间 | 工作时段/事件使用半开区间 `[start, end)`；相邻不算重叠 |
| 布尔 | 服务端 `boolean`；桌面 `INTEGER` 0/1 |

### 1.2 状态与同步

| 项 | 约定 |
| --- | --- |
| 服务端版本 | 每个可并发编辑实体有 `version bigint`；更新必须带 `baseVersion` |
| 桌面同步状态 | `SYNCED` / `PENDING` / `SENDING` / `LOCAL_ONLY` / `CONFLICT` / `FAILED` |
| 幂等 | 服务端 `operation_log.id` 同时承接 `/sync/push` 的 `operationId` 与在线 POST 的 `Idempotency-Key` |
| 删除 | 服务端软删 `deleted_at`；`change_log.op='DELETE'` 传播；不再单独建 tombstone 表 |
| 桌面删除 | 收到远端 DELETE 直接删本地缓存；若本地有未同步修改，转入 `outbox_op` 冲突，不静默覆盖 |

### 1.3 隐私边界

| 数据 | 服务端表 | 桌面表 | 共享可见 |
| --- | --- | --- | --- |
| 任务、事件、共享资料 | `task`、`fixed_event`、`resource` | `task_cache`、`event_cache`、`resource_cache` | 项目成员可见 |
| 本人剩余估时 | `task_user_state` | `task_cache.remaining_minutes` | **否** |
| 本人工作时段 | `work_session` | `session_local` | **否** |
| 番茄钟计时 | `focus_session` | 不落桌面业务表 | **否** |
| 来源原文/字段差异 | `intake_candidate` | `candidate_cache` | 仅确认范围内 |
| 第三方凭据 | `source_credential` | 不落桌面 | **否** |
| AI Key | 不落服务端业务表 | OS 凭据库，不落 SQLite | **否** |

---

## 2 总览

### 2.1 服务端 18 表

```text
身份与会话
  app_user
  refresh_session

工作区与协作
  workspace
  workspace_member
  project_invitation

任务
  task                 (stages_json)
  task_user_state      (本人剩余估时)

日程
  fixed_event
  work_session

资料
  resource
  resource_task_link

外部来源
  source_connection
  source_credential
  intake_candidate     (来源身份 + 字段差异 JSON)

番茄钟
  focus_session        (segments_json)

同步与审计
  operation_log        (sync + 在线幂等)
  change_log
  audit_log
```

### 2.2 桌面 SQLite 9 表

```text
+------------------+      +------------------+
| space_state      |      | outbox_op        |
| cursor/schema    |      | opId/baseVer     |
+------------------+      +------------------+
         |                         |
         v                         v
+------------------+      +------------------+
| workspace_cache  |<---->| task_cache       |
| members_json     |      | stages_json      |
| linked_course    |      | source_json      |
+------------------+      | remaining_min    |
         |                +------------------+
         v                         |
+------------------+               |
| event_cache      |               v
+------------------+      +------------------+
         |                | session_local    |
         v                | private          |
+------------------+      +------------------+
| resource_cache   |<---->| resource_link    |
+------------------+      +------------------+
         |
         v
+------------------+
| candidate_cache  |
| field_changes    |
+------------------+
```

---

## 3 服务端 PostgreSQL 表结构

### 3.1 `app_user` 账号

用途：账号、密码哈希、显示名、基础偏好。合并原 `user_settings`，不再单独建配置表。

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | uuid | 否 | app UUIDv7 | 主键 |
| `email` | citext | 否 | — | 登录邮箱；唯一 |
| `password_hash` | text | 否 | — | Argon2id 哈希，禁止明文 |
| `display_name` | varchar(80) | 否 | — | 显示名 |
| `status` | user_status | 否 | `ACTIVE` | `ACTIVE` / `DISABLED` / `DELETED` |
| `timezone` | text | 否 | `Asia/Shanghai` | 本期固定；保留字段便于未来扩展 |
| `week_starts_on` | smallint | 否 | `1` | 1=周一；1–7 |
| `default_focus_minutes` | integer | 否 | `25` | 默认番茄钟目标；1–240 |
| `created_at` | timestamptz | 否 | now() | 创建时间 |
| `updated_at` | timestamptz | 否 | now() | 更新时间 |
| `last_login_at` | timestamptz | 是 | null | 最近登录 |

约束/索引：

- PK `id`；UK `email`。
- `display_name` 去空白后非空；`week_starts_on` 1–7；`default_focus_minutes` 1–240。
- 删除账号采用状态变更/匿名化，不直接级联删除其共享项目历史。

### 3.2 `refresh_session` 刷新会话

用途：刷新令牌旋转、撤销、设备绑定。删除原 `device` 表，设备信息直接存在会话上。

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | uuid | 否 | app UUIDv7 | 主键 |
| `user_id` | uuid | 否 | — | FK `app_user.id`，`ON DELETE CASCADE` |
| `device_id` | text | 是 | null | 客户端设备 UUID/安装 ID；不建设备表 |
| `token_hash` | bytea | 否 | — | refresh token 的 SHA-256（32 字节） |
| `issued_at` | timestamptz | 否 | now() | 签发时间 |
| `expires_at` | timestamptz | 否 | — | 过期时间；30 天 |
| `revoked_at` | timestamptz | 是 | null | 撤销时间 |
| `rotated_from` | uuid | 是 | null | 旋转来源；FK 本表 |
| `last_used_at` | timestamptz | 是 | null | 最近使用 |
| `ip` | inet | 是 | null | 登录/刷新 IP |
| `user_agent` | text | 否 | `''` | 客户端标识；脱敏日志 |

约束/索引：

- PK `id`；UK `token_hash`；`token_hash` 长度 32；`expires_at > issued_at`。
- IDX `(user_id, expires_at)`、`(device_id)`。
- 旧 refresh 再次出现视为重放，撤销整个 token family。

### 3.3 `workspace` 工作区

用途：课程/项目、可见性、归档、共享确认、所有权交接。合并原 `workspace_share_snapshot` 和 `ownership_transfer`。

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | uuid | 否 | app UUIDv7 | 主键 |
| `owner_user_id` | uuid | 否 | — | 当前所有者；FK `app_user.id` |
| `kind` | workspace_kind | 否 | — | `COURSE` / `PROJECT` |
| `visibility` | workspace_visibility | 否 | `PERSONAL` | `PERSONAL` / `SHARED` |
| `name` | varchar(120) | 否 | — | 去空白后非空 |
| `description` | text | 否 | `''` | 简介 |
| `linked_course_id` | uuid | 是 | null | 项目关联课程；FK 本表；不可自指 |
| `status` | workspace_status | 否 | `ACTIVE` | `ACTIVE` / `ARCHIVED` |
| `sharing_confirmed_at` | timestamptz | 是 | null | 首次共享确认时间；`SHARED` 时必填 |
| `pending_owner_id` | uuid | 是 | null | 待接任成员；FK `app_user.id` |
| `pending_owner_requested_at` | timestamptz | 是 | null | 交接发起时间 |
| `version` | bigint | 否 | `1` | 乐观锁 |
| `created_at` | timestamptz | 否 | now() | 创建时间 |
| `updated_at` | timestamptz | 否 | now() | 更新时间 |
| `archived_at` | timestamptz | 是 | null | 归档时间 |

约束/索引：

- PK `id`；FK `owner_user_id`、`linked_course_id`、`pending_owner_id`。
- `kind='COURSE' ⇒ visibility='PERSONAL'`；`linked_course_id` 只能指向 `COURSE` 且不等于自身。
- `visibility='SHARED' ⇒ sharing_confirmed_at IS NOT NULL`。
- `status='ARCHIVED' ⇔ archived_at IS NOT NULL`。
- `pending_owner_id IS NOT NULL ⇔ pending_owner_requested_at IS NOT NULL`；待接任者不能等于当前 owner。
- IDX `(owner_user_id, status, kind)`、`(linked_course_id)`、`name` trigram。
- 共享确认后不自动把关联课程共享给成员；成员只见“私人课程”占位。

### 3.4 `workspace_member` 项目成员

用途：项目成员、角色、加入/离开历史。课程不建成员行，课程只有 owner。

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | uuid | 否 | app UUIDv7 | 主键 |
| `workspace_id` | uuid | 否 | — | FK `workspace.id`，`ON DELETE CASCADE` |
| `user_id` | uuid | 否 | — | FK `app_user.id`，`ON DELETE CASCADE` |
| `role` | member_role | 否 | `MEMBER` | `OWNER` / `MEMBER` |
| `status` | member_status | 否 | `ACTIVE` | `ACTIVE` / `LEFT` / `REMOVED` |
| `joined_at` | timestamptz | 否 | now() | 加入时间 |
| `left_at` | timestamptz | 是 | null | 离开/移除时间 |

约束/索引：

- PK `id`；FK `workspace_id`、`user_id`。
- 部分唯一索引 `(workspace_id, user_id) WHERE status='ACTIVE'`：同一项目不能重复活跃成员。
- 部分唯一索引 `(workspace_id) WHERE role='OWNER' AND status='ACTIVE'`：恰一活跃 owner。
- `status='ACTIVE' ⇒ left_at IS NULL`；非活跃必须 `left_at IS NOT NULL`。
- IDX `(user_id, status)`、`(workspace_id, status)`。
- 服务端在同一事务维护 `workspace.owner_user_id` 与该表的 OWNER 行。

### 3.5 `project_invitation` 项目邀请

用途：单次、7 天有效的邀请。删除 `role` 字段（邀请固定为 MEMBER），状态由 `accepted_at/revoked_at/expires_at` 派生。

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | uuid | 否 | app UUIDv7 | 主键 |
| `workspace_id` | uuid | 否 | — | FK `workspace.id`，`ON DELETE CASCADE` |
| `inviter_user_id` | uuid | 否 | — | FK `app_user.id` |
| `token_hash` | bytea | 否 | — | 邀请 token SHA-256（32 字节） |
| `expires_at` | timestamptz | 否 | — | 创建后 7 天 |
| `accepted_by` | uuid | 是 | null | 接受者；FK `app_user.id` |
| `accepted_at` | timestamptz | 是 | null | 接受时间 |
| `revoked_by` | uuid | 是 | null | 撤销者；FK `app_user.id` |
| `revoked_at` | timestamptz | 是 | null | 撤销时间 |
| `created_at` | timestamptz | 否 | now() | 创建时间 |

约束/索引：

- PK `id`；UK `token_hash`；`token_hash` 长度 32；`expires_at > created_at`。
- `accepted_at` 与 `revoked_at` 不能同时非空。
- IDX `(workspace_id, created_at DESC)`、`(token_hash)`。
- 接受时服务端串行校验 token、过期、撤销、是否已使用；已有活跃成员直接返回已有项目，不重复建成员。

### 3.6 `task` 任务

用途：任务主数据、截止、估时、负责人、共享阻碍、阶段（JSON）。合并原 `task_stage`、`task_stage_dependency`。

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | uuid | 否 | app UUIDv7 | 主键 |
| `workspace_id` | uuid | 是 | null | FK `workspace.id`；null=独立任务；创建后不可变 |
| `created_by` | uuid | 否 | — | FK `app_user.id` |
| `assignee_user_id` | uuid | 是 | null | 共享任务负责人；必须是活跃成员 |
| `title` | varchar(200) | 否 | — | 去空白后非空 |
| `description` | text | 否 | `''` | 描述 |
| `status` | task_status | 否 | `TODO` | `TODO` / `IN_PROGRESS` / `COMPLETED` |
| `priority` | task_priority | 否 | `MEDIUM` | `LOW` / `MEDIUM` / `HIGH` / `URGENT` |
| `deadline_date` | date | 是 | null | 仅日期截止 |
| `deadline_at` | timestamptz | 是 | null | 具体时刻截止；与 `deadline_date` 互斥 |
| `estimated_minutes` | integer | 是 | null | 任务总估时；1–100000 |
| `blocked_note` | text | 是 | null | 共享阻碍说明 |
| `stages` | jsonb | 否 | `'[]'` | 阶段数组；见 §6.1 |
| `version` | bigint | 否 | `1` | 乐观锁 |
| `last_modified_by` | uuid | 是 | null | 最后编辑者；FK `app_user.id` |
| `created_at` | timestamptz | 否 | now() | 创建时间 |
| `updated_at` | timestamptz | 否 | now() | 更新时间 |
| `completed_at` | timestamptz | 是 | null | 完成时间；与状态一致 |
| `deleted_at` | timestamptz | 是 | null | 软删除 |

约束/索引：

- PK `id`；FK `workspace_id`、`created_by`、`assignee_user_id`、`last_modified_by`。
- `title` 去空白后非空；`deadline_date` 与 `deadline_at` 至多一个；`estimated_minutes` 1–100000。
- `status='COMPLETED' ⇔ completed_at IS NOT NULL`；`jsonb_typeof(stages)='array'`。
- 触发器禁止更新 `workspace_id`。
- IDX `(workspace_id, status, updated_at DESC) WHERE deleted_at IS NULL`、`(created_by, status, updated_at DESC)`、`(assignee_user_id, status, updated_at DESC)`、`(deadline_date, deadline_at)`、`title` trigram。
- 阶段使用整任务 `version` 冲突；两个成员同时改不同阶段会以任务版本冲突处理。

### 3.7 `task_user_state` 本人任务状态

用途：每个用户对同一任务的“本人剩余估时”。共享任务行不能放这个字段，否则会泄露/互相覆盖。

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `task_id` | uuid | 否 | — | FK `task.id`，`ON DELETE CASCADE` |
| `user_id` | uuid | 否 | — | FK `app_user.id` |
| `remaining_minutes` | integer | 是 | null | 本人剩余估时；0–100000 |
| `version` | bigint | 否 | `1` | 乐观锁 |
| `updated_at` | timestamptz | 否 | now() | 更新时间 |

约束/索引：

- PK `(task_id, user_id)`；IDX `(user_id)`。
- 私有表；共享任务 DTO 永不返回；项目进度导出也不包含。

### 3.8 `fixed_event` 固定事件

用途：课程、会议、个人活动等有明确起止的固定占用。本期只做一次性事件，重复事件后置。

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | uuid | 否 | app UUIDv7 | 主键 |
| `workspace_id` | uuid | 是 | null | FK `workspace.id`；null=独立事件 |
| `owner_user_id` | uuid | 否 | — | FK `app_user.id`；创建者/个人事件所有者 |
| `title` | varchar(200) | 否 | — | 标题 |
| `description` | text | 否 | `''` | 说明 |
| `starts_at` | timestamptz | 否 | — | 开始 |
| `ends_at` | timestamptz | 否 | — | 结束；必须晚于开始 |
| `version` | bigint | 否 | `1` | 乐观锁 |
| `last_modified_by` | uuid | 是 | null | 最后编辑者；FK `app_user.id` |
| `created_at` | timestamptz | 否 | now() | 创建时间 |
| `updated_at` | timestamptz | 否 | now() | 更新时间 |
| `deleted_at` | timestamptz | 是 | null | 软删除 |

约束/索引：

- PK `id`；FK `workspace_id`、`owner_user_id`、`last_modified_by`。
- `ends_at > starts_at`；标题非空。
- IDX `(owner_user_id, starts_at, ends_at) WHERE deleted_at IS NULL`、`(workspace_id, starts_at, ends_at) WHERE deleted_at IS NULL`。
- 时间固定 `Asia/Shanghai`，不再存 `timezone`；重复规则字段后置。

### 3.9 `work_session` 工作时段

用途：本人安排的任务执行时段，私有。删除 `status`、`origin`、`is_locked`；用 `cancelled_at` 表达取消，锁定属于后置规则排程。

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | uuid | 否 | app UUIDv7 | 主键 |
| `task_id` | uuid | 是 | null | FK `task.id`；任务删除后置 null（脱链） |
| `owner_user_id` | uuid | 否 | — | 唯一可见者；FK `app_user.id` |
| `starts_at` | timestamptz | 否 | — | 开始 |
| `ends_at` | timestamptz | 否 | — | 结束；必须晚于开始 |
| `note` | text | 否 | `''` | 本人说明；脱链后保留 |
| `overlap_confirmed` | boolean | 否 | `false` | 用户是否明确确认保留重叠 |
| `cancelled_at` | timestamptz | 是 | null | 取消时间；历史仍保留 |
| `detached_at` | timestamptz | 是 | null | 任务删除后的脱链时间 |
| `version` | bigint | 否 | `1` | 乐观锁 |
| `created_at` | timestamptz | 否 | now() | 创建时间 |
| `updated_at` | timestamptz | 否 | now() | 更新时间 |

约束/索引：

- PK `id`；FK `task_id`（`ON DELETE RESTRICT`，任务只软删/由服务脱链）、`owner_user_id`。
- `ends_at > starts_at`；`(task_id IS NULL) ⇔ (detached_at IS NOT NULL)`。
- 不在数据库上做重叠排他约束：FR-S05 允许用户明确确认保留重叠；应用用 `[start,end)` 检查并写 `overlap_confirmed`。
- IDX `(owner_user_id, starts_at, ends_at)`、`(task_id, starts_at)`。
- 私有表；项目成员、项目导出、日志均不可见。

### 3.10 `resource` 资料

用途：工作区内的链接/文本资料。本期删掉附件相关字段，附件版本 Extended 后置。

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | uuid | 否 | app UUIDv7 | 主键 |
| `workspace_id` | uuid | 否 | — | FK `workspace.id`，`ON DELETE CASCADE` |
| `name` | varchar(200) | 否 | — | 名称；非空 |
| `description` | text | 否 | `''` | 说明 |
| `kind` | resource_kind | 否 | — | `LINK` / `TEXT`（本期不含 ATTACHMENT） |
| `url` | text | 是 | null | `LINK` 必填；http(s) |
| `text_content` | text | 是 | null | `TEXT` 必填 |
| `version` | bigint | 否 | `1` | 乐观锁 |
| `created_by` | uuid | 否 | — | FK `app_user.id` |
| `last_modified_by` | uuid | 是 | null | 最后编辑者；FK `app_user.id` |
| `created_at` | timestamptz | 否 | now() | 创建时间 |
| `updated_at` | timestamptz | 否 | now() | 更新时间 |
| `deleted_at` | timestamptz | 是 | null | 软删除 |

约束/索引：

- PK `id`；FK `workspace_id`、`created_by`、`last_modified_by`。
- `kind='LINK' ⇒ url IS NOT NULL`；`kind='TEXT' ⇒ text_content IS NOT NULL`；URL 只允许 http/https。
- IDX `(workspace_id, updated_at DESC) WHERE deleted_at IS NULL`、`name` trigram。
- 删除资料前展示关联任务数量；删除资料移除链接但不删除任务。

### 3.11 `resource_task_link` 任务—资料引用

用途：同一工作区内任务引用资料。删除字段 `created_by`（非核心事实）。

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `task_id` | uuid | 否 | — | FK `task.id`，`ON DELETE CASCADE` |
| `resource_id` | uuid | 否 | — | FK `resource.id`，`ON DELETE CASCADE` |
| `created_at` | timestamptz | 否 | now() | 创建时间 |

约束/索引：

- PK `(task_id, resource_id)`；IDX `(resource_id)`。
- 触发器校验任务与资料属于同一工作区；独立任务（无工作区）不能关联资料。

### 3.12 `source_connection` 外部来源连接

用途：Canvas/微信/QQ/课表的授权范围、状态、最近检索结果。凭据单独存 `source_credential`。

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | uuid | 否 | app UUIDv7 | 主键 |
| `user_id` | uuid | 否 | — | FK `app_user.id`，`ON DELETE CASCADE` |
| `source_type` | source_type | 否 | — | `CANVAS` / `WECHAT` / `QQ` / `TIMETABLE`；不含 MANUAL |
| `display_name` | varchar(120) | 否 | `''` | 展示名 |
| `status` | source_status | 否 | `CONNECTED` | `CONNECTED` / `PAUSED` / `REVOKED` / `ERROR` |
| `external_account_id` | text | 否 | `''` | 外部账号标识；可为空 |
| `scope` | jsonb | 否 | `'{}'` | 授权范围：账号、课程、群组、时间范围 |
| `last_success_at` | timestamptz | 是 | null | 最近成功检索 |
| `last_error_code` | varchar(64) | 是 | null | 最近失败码 |
| `last_error_message` | text | 是 | null | 用户可读失败原因；不含敏感原文 |
| `created_at` | timestamptz | 否 | now() | 创建时间 |
| `updated_at` | timestamptz | 否 | now() | 更新时间 |
| `revoked_at` | timestamptz | 是 | null | 撤回时间 |

约束/索引：

- PK `id`；FK `user_id`。
- `scope` 必须是 JSON object；`status='REVOKED' ⇔ revoked_at IS NOT NULL`。
- 部分唯一索引 `(user_id, source_type, external_account_id) WHERE external_account_id <> ''`。
- IDX `(user_id, source_type)`。
- 撤回后停止新读取；已确认任务保留。

### 3.13 `source_credential` 第三方凭据

用途：第三方来源凭据密文。保留独立表，避免与业务表混读；安全边界比少一张表更重要。

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | uuid | 否 | app UUIDv7 | 主键 |
| `source_connection_id` | uuid | 否 | — | FK `source_connection.id`，`ON DELETE CASCADE`；1:1 |
| `ciphertext` | bytea | 否 | — | AES-256-GCM 密文 |
| `nonce` | bytea | 否 | — | 12 字节随机数 |
| `key_version` | integer | 否 | `1` | 主密钥版本 |
| `created_at` | timestamptz | 否 | now() | 创建时间 |
| `rotated_at` | timestamptz | 是 | null | 轮换时间 |

约束/索引：

- PK `id`；UK `source_connection_id`；`nonce` 长度 12；`ciphertext` 非空。
- 明文只在来源适配器内存中出现；不返回客户端、不写日志、不进入共享项目。
- 生产环境主密钥由 KMS 管理；开发/验收使用独立密钥。

### 3.14 `intake_candidate` 来源候选

用途：外部来源/手工粘贴的候选、版本、字段差异、接受/忽略结果。合并原 `source_record` 和 `candidate_field_change`。

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | uuid | 否 | app UUIDv7 | 主键 |
| `user_id` | uuid | 否 | — | FK `app_user.id`，`ON DELETE CASCADE` |
| `source_connection_id` | uuid | 是 | null | FK `source_connection.id`；手工粘贴为 null |
| `source_type` | source_type | 否 | — | `CANVAS` / `WECHAT` / `QQ` / `TIMETABLE` / `MANUAL` |
| `external_account_id` | text | 否 | `''` | 外部账号标识 |
| `external_record_id` | text | 是 | null | 来源记录稳定 ID；手工粘贴可为 null |
| `source_version` | text | 否 | `'1'` | 来源内容版本 |
| `candidate_type` | candidate_type | 否 | `NEW` | `NEW` / `FIELD_CHANGE` / `SUSPECTED_DUPLICATE` |
| `status` | candidate_status | 否 | `PENDING` | `PENDING` / `ACCEPTED` / `IGNORED` / `SUPERSEDED` / `NEEDS_REVIEW` |
| `title` | varchar(200) | 否 | `''` | 候选标题 |
| `original_text` | text | 否 | `''` | 来源原文；仅本人可见/日志不写 |
| `source_url` | text | 是 | null | 来源链接 |
| `due_date` | date | 是 | null | 来源日期（仅日期） |
| `due_at` | timestamptz | 是 | null | 来源具体时刻；与 due_date 互斥 |
| `fields` | jsonb | 否 | `'{}'` | 结构化字段 |
| `field_changes` | jsonb | 否 | `'[]'` | 字段差异数组；见 §6.4 |
| `target_task_id` | uuid | 是 | null | 接受后任务；FK `task.id` |
| `target_event_id` | uuid | 是 | null | 接受后事件；FK `fixed_event.id` |
| `reviewed_source_version` | text | 是 | null | 已审阅的版本；避免同版本重复提示 |
| `accepted_at` | timestamptz | 是 | null | 接受时间 |
| `ignored_at` | timestamptz | 是 | null | 忽略时间 |
| `version` | bigint | 否 | `1` | 乐观锁 |
| `created_at` | timestamptz | 否 | now() | 创建时间 |
| `updated_at` | timestamptz | 否 | now() | 更新时间 |

约束/索引：

- PK `id`；FK `user_id`、`source_connection_id`、`target_task_id`、`target_event_id`。
- `due_date` 与 `due_at` 至多一个；`target_task_id` 与 `target_event_id` 至多一个；`fields` 为 object、`field_changes` 为 array。
- `status='ACCEPTED' ⇔ accepted_at IS NOT NULL`；`status='IGNORED' ⇔ ignored_at IS NOT NULL`。
- 部分唯一索引 `(user_id, source_type, external_account_id, external_record_id, source_version) WHERE external_record_id IS NOT NULL`：同一来源记录同一版本只一行。
- 部分唯一索引 `(user_id, source_type, external_account_id, external_record_id) WHERE status='ACCEPTED' AND external_record_id IS NOT NULL`：同一来源记录最多一个正式事项。
- IDX `(user_id, status, created_at DESC)`。
- 手工粘贴无可靠来源 ID 时走 `SUSPECTED_DUPLICATE` 审核，不依靠唯一键保证。

### 3.15 `focus_session` 番茄钟会话

用途：Android 在线番茄钟、前后台计时汇总、达标结果。合并原 `focus_segment` 为 `segments` JSON。

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | uuid | 否 | app UUIDv7 | 主键 |
| `client_session_id` | uuid | 否 | — | 客户端会话 ID；唯一，重试幂等 |
| `user_id` | uuid | 否 | — | FK `app_user.id`，`ON DELETE CASCADE` |
| `task_id` | uuid | 是 | null | FK `task.id`；任务删除后置 null |
| `work_session_id` | uuid | 是 | null | FK `work_session.id`；关联本人时段 |
| `target_minutes` | integer | 否 | — | 本次目标；1–720 |
| `status` | focus_status | 否 | `ACTIVE` | `ACTIVE` / `PAUSED` / `COMPLETED` / `CANCELLED` / `INTERRUPTED` |
| `result` | focus_result | 否 | `PENDING` | `PENDING` / `MET` / `NOT_MET` / `INTERRUPTED` |
| `started_at` | timestamptz | 否 | now() | 开始时间 |
| `ended_at` | timestamptz | 是 | null | 结束时间 |
| `segments` | jsonb | 否 | `'[]'` | 前后台时间片；见 §6.5 |
| `effective_seconds` | integer | 否 | `0` | 有效总秒数 = 前台 + 后台 |
| `foreground_seconds` | integer | 否 | `0` | 前台秒数 |
| `background_seconds` | integer | 否 | `0` | 后台秒数 |
| `note` | text | 否 | `''` | 个人反馈 |
| `version` | bigint | 否 | `1` | 乐观锁 |
| `created_at` | timestamptz | 否 | now() | 创建时间 |
| `updated_at` | timestamptz | 否 | now() | 更新时间 |

约束/索引：

- PK `id`；UK `client_session_id`；FK `user_id`、`task_id`、`work_session_id`。
- `target_minutes` 1–720；三个秒数字段 ≥0；`jsonb_typeof(segments)='array'`。
- 终态（COMPLETED/CANCELLED/INTERRUPTED）必须 `ended_at IS NOT NULL`；运行态必须为 null。
- 部分唯一索引 `(user_id) WHERE status IN ('ACTIVE','PAUSED')`：同一账号同时只有一个运行中番茄钟。
- IDX `(user_id, started_at DESC)`、`(task_id, started_at DESC)`。
- `effective_seconds >= 0.70 × target_minutes × 60` 时 `result='MET'`；应用以未舍入时间比较，展示比例最多 100%。
- 计时达标不改变任务状态；共享项目进度不返回番茄钟明细。

### 3.16 `operation_log` 操作幂等日志

用途：统一承接 `/sync/push` 的 `operationId` 与在线 POST 的 `Idempotency-Key`。合并原 `sync_operation` 和 `idempotency_key`。

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | uuid | 否 | 客户端生成 | 主键 = operationId / Idempotency-Key |
| `user_id` | uuid | 否 | — | FK `app_user.id`，`ON DELETE CASCADE` |
| `device_id` | text | 是 | null | 桌面设备 ID；Android 可空 |
| `entity_type` | varchar(40) | 否 | — | `TASK` / `WORK_SESSION` / `RESOURCE` 等 |
| `entity_id` | uuid | 否 | — | 目标实体；创建操作由客户端预生成 ID |
| `operation_type` | varchar(40) | 否 | — | `CREATE` / `UPDATE` / `DELETE` / `COMPLETE` / `ACCEPT` 等 |
| `base_version` | bigint | 是 | null | 乐观锁基准；CREATE 为 null |
| `payload_hash` | bytea | 否 | — | canonical JSON 的 SHA-256（32 字节） |
| `status` | operation_status | 否 | — | `APPLIED` / `REJECTED` / `CONFLICT` |
| `result` | jsonb | 是 | null | 上次结果；重试时原样返回 |
| `error_code` | varchar(64) | 是 | null | 失败码 |
| `created_at` | timestamptz | 否 | now() | 接收时间 |
| `applied_at` | timestamptz | 是 | null | 事务提交时间 |

约束/索引：

- PK `id`；FK `user_id`。
- `payload_hash` 长度 32；同一 `id` 的 payload hash 必须一致，否则返回 `OPERATION_ID_REUSED`。
- IDX `(user_id, created_at DESC)`。
- 单条操作独立事务；批量 push 中一条失败不回滚其他条。
- 不存完整 payload（隐私/体积）；只存 hash + result，需要排查时用请求 ID 关联脱敏日志。

### 3.17 `change_log` 变更日志

用途：按接收用户扇出的增量变更，供桌面 `/sync/pull` 使用。删除原 `tombstone` 表，删除用 `op='DELETE'` 表达。

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `seq` | bigserial | 否 | auto | 游标；单调递增 |
| `user_id` | uuid | 否 | — | 接收用户；FK `app_user.id` |
| `workspace_id` | uuid | 是 | null | 所属工作区；FK `workspace.id` |
| `entity_type` | varchar(40) | 否 | — | 实体类型 |
| `entity_id` | uuid | 否 | — | 实体 ID |
| `version` | bigint | 否 | — | 变更后版本；删除时为删除版本 |
| `op` | change_op | 否 | — | `UPSERT` / `DELETE` |
| `actor_user_id` | uuid | 是 | null | 操作者；FK `app_user.id` |
| `changed_at` | timestamptz | 否 | now() | 变更时间 |

约束/索引：

- PK `seq`；FK `user_id`、`workspace_id`、`actor_user_id`。
- IDX `(user_id, seq)`、`(entity_type, entity_id, version)`。
- 共享变更给每个 ACTIVE 成员写一行；成员移除后不再新增其行。
- 拉取时再次校验当前成员资格，防止旧 cursor 读到撤权后的内容。
- 保留 90 天；过期 cursor 返回 `SYNC_CURSOR_EXPIRED`，客户端全量重拉并保留 outbox。

### 3.18 `audit_log` 审计日志

用途：共享编辑、成员变更、附件下载、权限拒绝等安全追溯。可裁剪，但建议保留。

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | bigserial | 否 | auto | 主键 |
| `request_id` | text | 否 | — | 请求链路 ID |
| `actor_user_id` | uuid | 是 | null | 操作者；FK `app_user.id`，`ON DELETE SET NULL` |
| `action` | varchar(80) | 否 | — | `TASK_UPDATE`、`MEMBER_REMOVE`、`ATTACHMENT_DOWNLOAD` 等 |
| `entity_type` | varchar(40) | 是 | null | 实体类型 |
| `entity_id` | uuid | 是 | null | 实体 ID |
| `workspace_id` | uuid | 是 | null | 工作区；FK `workspace.id` |
| `result` | varchar(24) | 否 | — | `SUCCESS` / `DENIED` / `CONFLICT` / `ERROR` |
| `ip` | inet | 是 | null | 来源 IP |
| `user_agent` | text | 否 | `''` | 客户端标识 |
| `detail` | jsonb | 否 | `'{}'` | 脱敏上下文；禁止原文/令牌/Key |
| `created_at` | timestamptz | 否 | now() | 时间 |

约束/索引：

- PK `id`；FK `actor_user_id`、`workspace_id`。
- IDX `(actor_user_id, created_at DESC)`、`(entity_type, entity_id, created_at DESC)`、`(workspace_id, created_at DESC)`。
- 保留 180 天；不记录密码、令牌、AI Key、完整聊天原文、私人备注。

---

## 4 桌面 SQLite 表结构

### 4.1 `space_state` 本地空间状态

用途：未登录空间/账号空间的同步游标与 schema 版本。每个空间一个数据库文件，本表一行。

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `space_id` | TEXT | 否 | — | 主键；`guest` 或 `user:<uuid>` |
| `user_id` | TEXT | 是 | null | 对应账号；guest 为 null |
| `sync_cursor` | INTEGER | 否 | 0 | 服务端 `change_log.seq` |
| `last_sync_at` | INTEGER | 是 | null | 最近成功同步 epoch millis |
| `schema_version` | INTEGER | 否 | 1 | SQLite schema 版本 |
| `created_at` | INTEGER | 否 | — | epoch millis |
| `updated_at` | INTEGER | 否 | — | epoch millis |

约束/索引：

- PK `space_id`。
- 单行表；未登录空间不参与同步。

### 4.2 `workspace_cache` 工作区缓存

用途：工作区目录、概览、成员与关联课程展示。

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | TEXT | 否 | — | 主键；UUID |
| `kind` | TEXT | 否 | — | `COURSE` / `PROJECT` |
| `visibility` | TEXT | 否 | `PERSONAL` | `PERSONAL` / `SHARED` |
| `name` | TEXT | 否 | — | 名称 |
| `description` | TEXT | 否 | `''` | 简介 |
| `status` | TEXT | 否 | `ACTIVE` | `ACTIVE` / `ARCHIVED` |
| `linked_course_json` | TEXT | 是 | null | 见 §6.3；无权限时只存 `{"visible":false,"label":"私人课程"}` |
| `members_json` | TEXT | 否 | `'[]'` | 只读成员列表；见 §6.2 |
| `sharing_confirmed_at` | INTEGER | 是 | null | 共享确认时间 |
| `version` | INTEGER | 否 | 0 | 服务端版本 |
| `sync_state` | TEXT | 否 | `SYNCED` | 见 §1.2 |
| `updated_at` | INTEGER | 否 | — | epoch millis |
| `deleted_at` | INTEGER | 是 | null | 远端删除时间 |

约束/索引：

- PK `id`；CHECK `kind`、`visibility`、`status`。
- IDX `(kind, status, name)`、`(updated_at)`。
- 成员放 JSON，因为桌面只展示/选择负责人，不需要成员级查询；成员多时再拆 `member_cache`。
- 归档工作区内容只读；本人已有时段仍可调整。

### 4.3 `task_cache` 任务缓存

用途：任务列表、详情、离线编辑、阶段、来源展示、本人剩余估时。

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | TEXT | 否 | — | 主键；UUID |
| `workspace_id` | TEXT | 是 | null | FK `workspace_cache.id`；null=独立任务 |
| `title` | TEXT | 否 | — | 标题 |
| `description` | TEXT | 否 | `''` | 描述 |
| `status` | TEXT | 否 | `TODO` | `TODO` / `IN_PROGRESS` / `COMPLETED` |
| `priority` | TEXT | 否 | `MEDIUM` | `LOW` / `MEDIUM` / `HIGH` / `URGENT` |
| `deadline_date` | TEXT | 是 | null | `YYYY-MM-DD`；与 `deadline_at` 互斥 |
| `deadline_at` | INTEGER | 是 | null | epoch millis；与 `deadline_date` 互斥 |
| `estimated_minutes` | INTEGER | 是 | null | 任务总估时；≥1 |
| `remaining_minutes` | INTEGER | 是 | null | 本人剩余估时；**仅我**；≥0 |
| `assignee_user_id` | TEXT | 是 | null | 负责人 ID |
| `assignee_name` | TEXT | 是 | null | 负责人显示名；冗余便于离线展示 |
| `blocked_note` | TEXT | 是 | null | 共享阻碍说明 |
| `stages_json` | TEXT | 否 | `'[]'` | 阶段数组；见 §6.1 |
| `source_json` | TEXT | 是 | null | 来源展示；见 §6.6 |
| `last_modified_by` | TEXT | 是 | null | 最后编辑者显示名/ID |
| `last_modified_at` | INTEGER | 是 | null | 最后编辑时间 |
| `version` | INTEGER | 否 | 0 | 服务端版本 |
| `sync_state` | TEXT | 否 | `SYNCED` | 本地/同步状态 |
| `updated_at` | INTEGER | 否 | — | epoch millis |
| `deleted_at` | INTEGER | 是 | null | 远端删除时间 |

约束/索引：

- PK `id`；FK `workspace_id` `ON DELETE CASCADE`。
- `title` 非空；`deadline_date` 与 `deadline_at` 至多一个；`estimated_minutes >= 1`；`remaining_minutes >= 0`。
- IDX `(workspace_id, status, deadline_date)`、`(status, deadline_at)`、`(updated_at)`。
- `remaining_minutes` 只用于本人视图，不能在共享详情/项目看板显示。
- 阶段和依赖放 `stages_json`；如果阶段需要独立冲突/独立版本，再拆 `task_stage` 表。

### 4.4 `event_cache` 固定事件缓存

用途：课程、会议、个人活动等固定占用。

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | TEXT | 否 | — | 主键；UUID |
| `workspace_id` | TEXT | 是 | null | FK `workspace_cache.id`；null=独立事件 |
| `title` | TEXT | 否 | — | 标题 |
| `description` | TEXT | 否 | `''` | 说明 |
| `starts_at` | INTEGER | 否 | — | epoch millis |
| `ends_at` | INTEGER | 否 | — | epoch millis；必须晚于开始 |
| `last_modified_by` | TEXT | 是 | null | 最后编辑者显示名/ID |
| `last_modified_at` | INTEGER | 是 | null | 最后编辑时间 |
| `version` | INTEGER | 否 | 0 | 服务端版本 |
| `sync_state` | TEXT | 否 | `SYNCED` | 见 §1.2 |
| `updated_at` | INTEGER | 否 | — | epoch millis |
| `deleted_at` | INTEGER | 是 | null | 远端删除时间 |

约束/索引：

- PK `id`；FK `workspace_id` `ON DELETE CASCADE`。
- CHECK `ends_at > starts_at`；标题非空。
- IDX `(starts_at, ends_at)`、`(workspace_id, starts_at)`。
- 重复事件字段后置；本期只做一次性事件。

### 4.5 `session_local` 本人工作时段

用途：本人安排的任务执行时间，私有。桌面不展示他人时段。

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | TEXT | 否 | — | 主键；UUID |
| `task_id` | TEXT | 是 | null | FK `task_cache.id`；任务删除后置 null |
| `starts_at` | INTEGER | 否 | — | epoch millis |
| `ends_at` | INTEGER | 否 | — | epoch millis；必须晚于开始 |
| `note` | TEXT | 否 | `''` | 本人说明；脱链后保留 |
| `overlap_confirmed` | INTEGER | 否 | 0 | 0/1；用户是否确认保留重叠 |
| `cancelled_at` | INTEGER | 是 | null | 取消时间；历史保留 |
| `detached_at` | INTEGER | 是 | null | 任务删除后脱链时间 |
| `version` | INTEGER | 否 | 0 | 服务端版本 |
| `sync_state` | TEXT | 否 | `SYNCED` | 见 §1.2 |
| `updated_at` | INTEGER | 否 | — | epoch millis |

约束/索引：

- PK `id`；FK `task_id` `ON DELETE RESTRICT`；任务删除时由应用先脱链（`task_id=null, detached_at=now`），再清理任务。
- CHECK `ends_at > starts_at`；`(task_id IS NULL) = (detached_at IS NOT NULL)`。
- IDX `(starts_at, ends_at)`、`(task_id, starts_at)`。
- 不再存 `status`；用 `cancelled_at` 表达取消。半开区间 `[start,end)`；相邻不算重叠。

### 4.6 `resource_cache` 资料缓存

用途：工作区链接/文本资料。

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | TEXT | 否 | — | 主键；UUID |
| `workspace_id` | TEXT | 否 | — | FK `workspace_cache.id`，`ON DELETE CASCADE` |
| `name` | TEXT | 否 | — | 名称；非空 |
| `description` | TEXT | 否 | `''` | 说明 |
| `kind` | TEXT | 否 | — | `LINK` / `TEXT` |
| `url` | TEXT | 是 | null | `LINK` 必填；http(s) |
| `text_content` | TEXT | 是 | null | `TEXT` 必填 |
| `last_modified_by` | TEXT | 是 | null | 最后编辑者显示名/ID |
| `last_modified_at` | INTEGER | 是 | null | 最后编辑时间 |
| `version` | INTEGER | 否 | 0 | 服务端版本 |
| `sync_state` | TEXT | 否 | `SYNCED` | 见 §1.2 |
| `updated_at` | INTEGER | 否 | — | epoch millis |
| `deleted_at` | INTEGER | 是 | null | 远端删除时间 |

约束/索引：

- PK `id`；FK `workspace_id`。
- CHECK `kind='LINK' ⇒ url IS NOT NULL`；`kind='TEXT' ⇒ text_content IS NOT NULL`。
- IDX `(workspace_id, updated_at)`、`(name)`。
- 附件缓存后置；需要时新增 `attachment_cache`，不改变本表主结构。

### 4.7 `resource_link` 资料引用

用途：任务引用资料；解除引用不删除资料。

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `task_id` | TEXT | 否 | — | FK `task_cache.id`，`ON DELETE CASCADE` |
| `resource_id` | TEXT | 否 | — | FK `resource_cache.id`，`ON DELETE CASCADE` |
| `created_at` | INTEGER | 否 | — | epoch millis |
| `sync_state` | TEXT | 否 | `SYNCED` | 见 §1.2 |

约束/索引：

- PK `(task_id, resource_id)`；IDX `(resource_id)`。
- 应用层保证任务与资料同工作区；服务端再次校验。
- 删除资料时移除引用但保留任务。

### 4.8 `candidate_cache` 候选缓存

用途：来源候选、原文、字段差异、接受/忽略状态。支持离线处理已有候选。

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | TEXT | 否 | — | 主键；UUID |
| `source_type` | TEXT | 否 | — | `CANVAS` / `WECHAT` / `QQ` / `TIMETABLE` / `MANUAL` |
| `external_account_id` | TEXT | 否 | `''` | 外部账号标识 |
| `external_record_id` | TEXT | 是 | null | 来源记录稳定 ID |
| `source_version` | TEXT | 否 | `'1'` | 来源内容版本 |
| `candidate_type` | TEXT | 否 | `NEW` | `NEW` / `FIELD_CHANGE` / `SUSPECTED_DUPLICATE` |
| `status` | TEXT | 否 | `PENDING` | `PENDING` / `ACCEPTED` / `IGNORED` / `SUPERSEDED` / `NEEDS_REVIEW` |
| `title` | TEXT | 否 | `''` | 候选标题 |
| `original_text` | TEXT | 否 | `''` | 来源原文；仅本人可见 |
| `source_url` | TEXT | 是 | null | 来源链接 |
| `due_date` | TEXT | 是 | null | `YYYY-MM-DD` |
| `due_at` | INTEGER | 是 | null | epoch millis；与 due_date 互斥 |
| `fields_json` | TEXT | 否 | `'{}'` | 结构化字段 |
| `field_changes_json` | TEXT | 否 | `'[]'` | 字段差异；见 §6.4 |
| `target_task_id` | TEXT | 是 | null | 接受后任务 ID |
| `target_event_id` | TEXT | 是 | null | 接受后事件 ID |
| `reviewed_source_version` | TEXT | 是 | null | 已审阅版本 |
| `version` | INTEGER | 否 | 0 | 服务端版本 |
| `sync_state` | TEXT | 否 | `SYNCED` | 见 §1.2 |
| `updated_at` | INTEGER | 否 | — | epoch millis |

约束/索引：

- PK `id`；CHECK `source_type`、`candidate_type`、`status`。
- CHECK `due_date` 与 `due_at` 至多一个；`target_task_id` 与 `target_event_id` 至多一个。
- IDX `(status, updated_at)`、`(source_type, external_record_id)`。
- 手工粘贴无可靠来源 ID 时只做疑似重复提示，不做唯一键保证。

### 4.9 `outbox_op` 待同步操作

用途：本地事务产生的待同步操作；同时承载冲突比较信息，不再单独建 conflict 表。

| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `operation_id` | TEXT | 否 | — | 主键；UUIDv7，重试保持不变 |
| `entity_type` | TEXT | 否 | — | `WORKSPACE` / `TASK` / `EVENT` / `WORK_SESSION` / `RESOURCE` / `RESOURCE_LINK` / `TASK_MY_STATE` / `CANDIDATE` |
| `entity_id` | TEXT | 否 | — | 目标实体 ID |
| `operation_type` | TEXT | 否 | — | `CREATE` / `UPDATE` / `DELETE` / `COMPLETE` / `REOPEN` / `ACCEPT` / `IGNORE` |
| `base_version` | INTEGER | 是 | null | 提交时本地基线版本；CREATE 为 null |
| `payload_json` | TEXT | 否 | — | 本次变更字段（只放允许更新的字段） |
| `status` | TEXT | 否 | `PENDING` | `PENDING` / `SENDING` / `APPLIED` / `CONFLICT` / `REJECTED` / `FAILED` |
| `attempts` | INTEGER | 否 | 0 | 尝试次数 |
| `last_error_code` | TEXT | 是 | null | 最近错误码 |
| `base_payload_json` | TEXT | 是 | null | 基线快照；冲突三列比较用 |
| `server_payload_json` | TEXT | 是 | null | 服务端最新记录；冲突时展示 |
| `created_at` | INTEGER | 否 | — | epoch millis |
| `updated_at` | INTEGER | 否 | — | epoch millis |

约束/索引：

- PK `operation_id`；CHECK `status`。
- IDX `(status, created_at)`、`(entity_type, entity_id)`。
- 本地事务：业务表变更 + 本表插入必须同一事务；commit 后才显示“已保存到本地”。
- 同一 `operation_id` 只发送一次结果；`APPLIED` 后可按保留策略清理。
- `base_payload_json` 只在需要冲突比较时保存；避免长期存全量私人数据。

---

## 5 裁剪与合并对照

| 原设计 | 最终处理 | 原因 |
| --- | --- | --- |
| `user_settings` | 合并进 `app_user` | 1:1 且字段少 |
| `device` | 删除 | 当前无设备管理 UI；会话表存 `device_id` |
| `workspace_share_snapshot` | 删除 | 保留 `sharing_confirmed_at` 足够 |
| `ownership_transfer` | 合并进 `workspace.pending_owner_*` | 同时只允许一个待处理交接 |
| `task_stage`、`task_stage_dependency` | 合并为 `task.stages` JSON | 阶段从属任务，数量小；代价是阶段级冲突变粗 |
| `source_record` | 合并进 `intake_candidate` 唯一键 | 来源身份 = 用户+来源+账号+记录 ID |
| `candidate_field_change` | 合并为 `intake_candidate.field_changes` JSON | 字段差异是候选从属数据 |
| `focus_segment` | 合并为 `focus_session.segments` JSON | 在线会话一次提交多个片段；应用校验去重/重叠 |
| `sync_operation` + `idempotency_key` | 合并为 `operation_log` | 同步与在线 POST 共用幂等 |
| `tombstone` | 删除 | `deleted_at` + `change_log.op='DELETE'` 足够 |
| `outbox_event`、`reminder` | 删除/后置 | 非核心功能 |
| `resource_attachment`、`current_attachment_id` | 后置 | 附件版本属于 Extended |
| `fixed_event` recurrence/series 字段 | 后置 | 重复事件属于 Extended |
| `work_session.is_locked/origin/status` | 删除/后置 | 取消用 `cancelled_at`；锁定属于规则排程 |
| `workspace.created_by/sharing_confirmed_by` | 删除 | 与 owner/审计重复 |
| `workspace_member.invited_by/version` | 删除 | 邀请表已有 inviter；成员变更在线串行 |
| `task.deleted_by` | 删除 | 审计可记录 |
| `resource_task_link.created_by` | 删除 | 非核心事实 |
| `intake_candidate.occurred_at/reviewed_at` | 删除 | `reviewed_source_version` 足够判断重复提示 |

---

## 6 JSON 字段定义

### 6.1 `task.stages` / `task_cache.stages_json`

```json
[
  {
    "id": "0192f4c8-...",
    "name": "信息架构",
    "goal": "页面清单与导航",
    "sortOrder": 1,
    "estimatedMinutes": 30,
    "status": "COMPLETED",
    "dependsOn": [],
    "completedAt": "2026-11-02T10:00:00+08:00"
  }
]
```

规则：

- `id` 在任务内唯一；`sortOrder` 决定展示顺序。
- `dependsOn` 引用同任务阶段 ID；应用层校验无环、无自依赖。
- 阶段完成不自动完成父任务；阶段不是时间段。
- 服务端把整个 `stages` 作为任务字段保存，更新以任务 `version` 冲突。

### 6.2 `workspace_cache.members_json`

```json
[
  {
    "userId": "0192f4c8-...",
    "displayName": "陈楠",
    "role": "OWNER",
    "status": "ACTIVE"
  }
]
```

只读缓存；用于负责人选择、成员展示。成员很多/需要筛选时再拆 `member_cache`。

### 6.3 `workspace_cache.linked_course_json`

```json
{ "visible": true, "id": "0192f4c8-...", "name": "软件工程" }
```

无课程权限时：

```json
{ "visible": false, "label": "私人课程" }
```

不存课程描述、教师、课表；避免泄露。

### 6.4 `intake_candidate.field_changes` / `candidate_cache.field_changes_json`

```json
[
  {
    "field": "deadlineAt",
    "oldSourceValue": "2026-11-12T23:59:00+08:00",
    "localValue": "2026-11-13T23:59:00+08:00",
    "newSourceValue": "2026-11-14T23:59:00+08:00",
    "decision": "PENDING"
  }
]
```

`decision`：`PENDING` / `ACCEPT` / `REJECT`。接受变更只写被接受的字段，保留负责人、时段、资料关联。

### 6.5 `focus_session.segments`

```json
[
  {
    "clientSegmentId": "0192f4c8-...",
    "kind": "FOREGROUND",
    "startedAt": "2026-11-02T19:00:00+08:00",
    "endsAt": "2026-11-02T19:30:00+08:00",
    "durationSeconds": 1800
  },
  {
    "clientSegmentId": "0192f4c8-...",
    "kind": "BACKGROUND",
    "startedAt": "2026-11-02T19:30:00+08:00",
    "endsAt": "2026-11-02T19:42:00+08:00",
    "durationSeconds": 720
  }
]
```

规则：

- 同一 `clientSegmentId` 只计一次；重试幂等。
- 同会话片段不得重叠；应用层校验。
- 断网不补算；会话中断只保留最后可确认片段。
- `effectiveSeconds = foregroundSeconds + backgroundSeconds`。

### 6.6 `task_cache.source_json`

```json
{
  "type": "WECHAT",
  "candidateId": "0192f4c8-...",
  "sourceUrl": "https://..."
}
```

只存来源展示所需的最小信息；原文留在 `candidate_cache`，避免任务表复制敏感长文本。

---

## 7 索引与查询

### 7.1 服务端关键查询

| 查询 | 索引 |
| --- | --- |
| 今日/周视图（本人事件） | `fixed_event(owner_user_id, starts_at, ends_at)` |
| 今日/周视图（本人时段） | `work_session(owner_user_id, starts_at, ends_at)` |
| 工作区任务列表 | `task(workspace_id, status, updated_at)` |
| 我的任务/负责人任务 | `task(created_by, status, updated_at)`、`task(assignee_user_id, status, updated_at)` |
| 截止提醒 | `task(deadline_date, deadline_at)` |
| 任务/资料搜索 | `task.title`、`resource.name` trigram |
| 来源候选 | `intake_candidate(user_id, status, created_at)` |
| 同步拉取 | `change_log(user_id, seq)` |
| 番茄钟历史 | `focus_session(user_id, started_at)` |
| 审计检索 | `audit_log(actor_user_id, created_at)`、`(workspace_id, created_at)` |

### 7.2 桌面关键查询

| 查询 | 索引 |
| --- | --- |
| 今日/周视图 | `session_local(starts_at, ends_at)`、`event_cache(starts_at, ends_at)` |
| 工作区任务 | `task_cache(workspace_id, status, deadline_date)` |
| 截止任务 | `task_cache(status, deadline_at)` |
| 资料列表 | `resource_cache(workspace_id, updated_at)` |
| 待同步队列 | `outbox_op(status, created_at)` |
| 冲突定位 | `outbox_op(entity_type, entity_id)` |
| 候选列表 | `candidate_cache(status, updated_at)` |

桌面搜索建议：

- 验收规模 1000 任务/200 资料；按工作区/状态/时间走索引，标题/名称在 Kotlin 内存过滤即可。
- 不把搜索逻辑压在 `json_extract` 上；JSON 字段只在 Kotlin 反序列化使用，避免依赖 SQLCipher 的 JSON1 构建。

---

## 8 隐私与同步规则

1. **服务端共享 DTO 白名单**：共享任务只返回任务字段、阶段、负责人、阻碍与最后编辑信息；`work_session`、`task_user_state`、`focus_session` 永不进入共享响应/项目导出。
2. **桌面共享页面**：`task_cache.remaining_minutes`、`session_local` 只在本人视图显示；共享看板不读这些字段。
3. **离线快照**：桌面共享内容可能过期；重连先鉴权再上传，失权则清理共享标题/来源/资料访问，本人时段按 BR12 脱链。
4. **删除任务**：服务端软删任务；本人时段由服务端/桌面事务脱链，保留时间与本人说明；其他成员时段不动；`change_log.op='DELETE'` 传播。
5. **同步幂等**：`operation_log` 同一 `id` + `payload_hash` 返回上次结果；同 ID 不同 payload 拒绝；`change_log.seq` 作为游标。
6. **桌面事务**：业务表变更 + `outbox_op` 插入同一事务；服务端确认后才显示“已同步”。
7. **AI Key**：只存 OS 凭据库，不进入 SQLite 业务表、不进入同步、不进入日志。
8. **第三方凭据**：只存服务端 `source_credential` 密文；不返回客户端。

---

## 9 API 影响

| 变化 | 说明 |
| --- | --- |
| `PATCH /tasks/{taskId}` | 直接接收 `stages: [...]`；删除 `/tasks/{id}/stages`、`/stages/dependencies` 端点 |
| `/sync/pull` | 返回客户端投影：任务自带 `stages`、`source`、`assignee`；工作区自带 `members` |
| `operation_log` | `/sync/push` 的 `operationId` 与在线 POST 的 `Idempotency-Key` 共用 |
| `resource` | 本期无附件字段/端点；附件版本 Extended 再加 |
| `fixed_event` | 本期无重复规则字段；重复事件 Extended 再加 |
| `focus-sessions` | 继续用 `/focus-sessions/{id}/segments` 上报片段；服务端存 JSON |
| `intake_candidate` | 候选接受接口携带 `candidateVersion` + `fields` + 可选字段决策 |
| 错误码 | 保留 `VERSION_CONFLICT`、`MEMBERSHIP_REVOKED`、`OPERATION_ID_REUSED`、`SYNC_CURSOR_EXPIRED` 等 |

---

## 10 迁移与扩展预留

### 10.1 从 32 表基线迁移

当前项目没有生产数据；建议直接以本 18 表基线重建。如果已有测试数据，按以下顺序迁移：

1. `user_settings` → `app_user` 列。
2. `workspace_share_snapshot` → 回填 `workspace.sharing_confirmed_at` 后删除。
3. `ownership_transfer` → 仅保留 PENDING 到 `workspace.pending_owner_*`。
4. `task_stage`/`task_stage_dependency` → 生成 `task.stages` JSON。
5. `source_record` + `candidate_field_change` → 合并进 `intake_candidate`；保留唯一键与字段差异 JSON。
6. `focus_segment` → 生成 `focus_session.segments` JSON，重算三个秒数字段。
7. `sync_operation` + `idempotency_key` → 合并为 `operation_log`。
8. `tombstone` → 删除；用 `deleted_at` + `change_log.op='DELETE'`。
9. `device` → 删除；`refresh_session.device_id` 保留。
10. 附件/重复事件/规则排程/提醒表与字段暂不迁移。

### 10.2 Extended 回加位置

| 能力 | 回加方式 |
| --- | --- |
| 附件版本 | 新增 `resource_attachment`；`resource.current_attachment_id`；`resource.kind` 增加 `ATTACHMENT` |
| 重复事件 | `fixed_event` 增加 `recurrence_rule`、`recurrence_until`、`series_id`、`is_exception` |
| 规则排程锁定 | `work_session` 增加 `is_locked`；可新增 `scheduling_draft` 临时表 |
| 提醒/通知 | 新增 `reminder`；可选 `outbox_event` |
| 设备管理 | 新增 `device`；`refresh_session.device_id` 改为 FK |

### 10.3 客户端迁移

- 当前客户端示例是 `Task(id: Long, title: String)`，属于内存演示；接入 SQLite 前应改为 `TEXT` UUID（领域层可用 `TaskId` 值类），否则会多出 ID 映射表。
- SQLDelight 使用 `.sq`/`.sqm` 管理 schema 与迁移；每次升级递增 `space_state.schema_version`。
- 建议开启 `PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;`。
- SQLCipher 密钥从 OS Keychain/Credential Manager 获取；退出登录锁库，不删除未同步数据。

---

## 11 验收清单

- [ ] 服务端空库能按 18 表基线建表；所有唯一索引/检查约束/触发器生效。
- [ ] 同一项目第二个活跃 OWNER 被拒绝；移除成员后项目读/写/下载失败。
- [ ] 同一来源记录接受两次只有一个正式事项；忽略同版本不重复提示。
- [ ] 同一用户并发启动两个番茄钟被拒绝；同一会话片段重复上报只计一次。
- [ ] 跨工作区任务—资料关联被拒绝。
- [ ] 任务创建工作区后不可改归属。
- [ ] 完成任务时任务状态与本人未来时段同一事务；失败都不变。
- [ ] 共享接口不返回 `work_session`、`task_user_state`、`focus_session`。
- [ ] 桌面离线创建任务 + `outbox_op` 同事务提交；重启后仍在。
- [ ] 桌面同一操作重试三次只生效一次；版本冲突保留草稿并可比较。
- [ ] 桌面收到远端删除后清理缓存；本地未同步修改转入冲突而非静默覆盖。
- [ ] 桌面 `remaining_minutes`、`session_local` 不出现在共享页面。
- [ ] 附件/重复事件/规则排程字段未出现在本期 API 与 UI 中。
