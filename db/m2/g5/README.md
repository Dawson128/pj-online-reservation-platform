# G5 迁移目录

首次启动自动建表、新增/修改表的步骤和模板见 [数据库建表与迁移指南](../../README.md)。本目录 SQL 是物理结构的依据，本文是阅读索引，不代替执行迁移。

`V20261102_093706_5_001__G5_identity_and_sessions.sql` 创建统一账户、院系、角色、权限、角色授权、角色绑定、会话、限速和审计/outbox 表。账户的 `user_code` 为登录用户名，`password_hash` 只保存带盐 PBKDF2 散列；会话和 CSRF 只保存 SHA-256 摘要。

迁移中没有初始密码。权限和六类角色由独立幂等种子初始化；管理员仅在显式开启 G5_BOOTSTRAP_ENABLED 并提供私有密码时创建。种子重跑不会重置已有密码或角色授权。G5-006 新增迁移将账户默认首登标记设为 false，并清理既有强制改密限制；旧迁移保持不变。

同一 m2 schema 内，G5 与 G2 迁移使用同一 Flyway 历史，按完整版本键排序。不要在 G5 模块中另启动迁移器。

G5-007 的第 003 项迁移保存管理响应缓存及长期创建意图；G5-009 的 `V20261103_010000_5_004__G5_role_creation_intents.sql` 以新迁移扩展意图类型为 ROLE，并增加按角色查找绑定账户的索引。角色、授权、账户版本、会话撤销和审计/outbox 在同一管理事务内提交；本迁移不重置任何账户或内置授权。

G5-010 第 005 项迁移新增 business_config 单例及 g5_config_history，按文档初始化默认值和版本 1。后续追加版本与操作者，启动不覆盖；配置快照、事件/outbox、幂等结果同事务提交，不改已有账号、角色或密码。

G5-013 新增 V20261103_072532_5_006__G5_reliable_outbox.sql：g5_operation、g5_outbox_attempt、原 outbox 同事务触发器及前向回填。原事件/密码/账号配置不改，历史三种 outbox 状态分别建立对应操作。应用运行账户需两表所需 DML/查询权限，不需要 DDL。已执行迁移保留校验和。

## 现有应用表清单

截至 2026-11-03，6 个版本迁移共创建下列 17 张应用表，均归 G5、位于 m2；`m2.flyway_schema_history` 由 Flyway 维护，不计入应用表。这里只列关键字段/约束，完整类型、默认值、索引及后续变化须阅读对应 SQL。

| 表 | 用途与关键字段/约束 | 创建迁移 |
| --- | --- | --- |
| department | 院系字典；UUID 主键、code 唯一、enabled、正 version | [001](V20261102_093706_5_001__G5_identity_and_sessions.sql) |
| account | 统一账户；user_code 唯一、department_id 外键、password_hash、account/permission 版本 | [001](V20261102_093706_5_001__G5_identity_and_sessions.sql) |
| permission | 动作目录；action 主键、owner_group 为 G1—G6 | [001](V20261102_093706_5_001__G5_identity_and_sessions.sql) |
| role | 角色；key/name 各自唯一、built_in、正 version | [001](V20261102_093706_5_001__G5_identity_and_sessions.sql) |
| role_grant | 角色的动作与范围；SCHOOL/DEPARTMENT/ROOM/SYSTEM 及互斥范围字段；包含 NULL 的组合仍唯一 | [001](V20261102_093706_5_001__G5_identity_and_sessions.sql) |
| role_binding | 账户绑定角色；account_id + role_id 唯一 | [001](V20261102_093706_5_001__G5_identity_and_sessions.sql) |
| session | 服务端会话；token_hash 唯一、csrf_hash、身份/权限版本、到期/撤销时间 | [001](V20261102_093706_5_001__G5_identity_and_sessions.sql) |
| g5_auth_throttle | 登录限速；bucket + subject_hash 联合主键，失败数非负 | [001](V20261102_093706_5_001__G5_identity_and_sessions.sql) |
| g5_audit_event | G5 本地不可变事件记录；event_id 主键、event JSONB；不代表 G6 已集中接收 | [001](V20261102_093706_5_001__G5_identity_and_sessions.sql) |
| g5_outbox | 本组待投递事件；event_id 引用本地事件，状态 PENDING/DELIVERED/BLOCKED | [001](V20261102_093706_5_001__G5_identity_and_sessions.sql) |
| g5_admin_guard | id=1 的保护锁行，供最后有效系统管理员保护事务使用 | [001](V20261102_093706_5_001__G5_identity_and_sessions.sql) |
| g5_idempotency | 管理响应缓存；actor + method + path + request_key 联合主键、请求 HMAC、24 小时到期默认值 | [003](V20261102_150054_5_003__G5_management_idempotency.sql) |
| g5_creation_intent | 长期创建意图；同一联合主键、请求 HMAC、原 object_id；与短期缓存分开 | [003](V20261102_150054_5_003__G5_management_idempotency.sql) |
| business_config | id=1 的全局参数单例，正 version、生效时间和五项范围约束 | [005](V20261103_050000_5_005__G5_business_configuration.sql) |
| g5_config_history | 参数追加历史；version 主键、操作者及 values JSONB | [005](V20261103_050000_5_005__G5_business_configuration.sql) |
| g5_operation | 可靠投递操作；outbox_event_id 唯一、状态、重试/租约、版本；RUNNING 必须有完整租约 | [006](V20261103_072532_5_006__G5_reliable_outbox.sql) |
| g5_outbox_attempt | 每次投递尝试；operation_id + sequence 唯一、租约令牌、开始/结束时间和结果 | [006](V20261103_072532_5_006__G5_reliable_outbox.sql) |

注意后续迁移的叠加结果：

- [002](V20261102_143705_5_002__G5_optional_password_change.sql) 将 account.must_change_password 默认值改为 false；新密码 8—24 字符由应用校验，旧密码不会因此重置。
- [004](V20261103_010000_5_004__G5_role_creation_intents.sql) 将 g5_creation_intent.object_kind 扩展为 ACCOUNT/DEPARTMENT/ROLE，并增加按 role_id 查绑定账户的索引。
- [006](V20261103_072532_5_006__G5_reliable_outbox.sql) 还创建本组 outbox 插入触发器和函数，并回填旧事件对应的操作记录；不能只复制其中两条 CREATE TABLE 来替代完整迁移。

后续模块保存外部账户/院系 ID 或调用 G5 接口，不直接连接本表实现鉴权。不要创建第二套账户表来保存 G5 的密码或可变权限副本。
