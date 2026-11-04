# G5 全局参数配置

本功能按技术文档 v2 §4.3、G5-P18/P19/I07/L03 与 G5-E01 实现。G1、G2、G6 尚未接入，G5 当前负责保存配置、校验和提供当前版本，不声称预约、审批、通知消费业务已经生效。

## 管理员使用

管理员从顶部“全局参数”进入 `/admin/config`。页面显示版本、生效时间、允许范围、默认值和用途。仅实际 `SYSTEM/system:manage` 可查看或修改；普通用户不能进入页面或调用管理接口。

| 字段 | 含义 | 默认 | 允许整数范围 | 业务 owner |
| --- | --- | --- | --- | --- |
| reservationMaxHours | 个人预约时长上限 | 4 小时 | 1—8 | G1 |
| reservationAdvanceDays | 个人预约提前天数 | 7 天 | 1—7 | G1 |
| applicationAdvanceDays | 整室申请提前天数 | 14 天 | 1—14 | G2 |
| scanIntervalSeconds | 自动检查间隔 | 10 秒 | 1—30 | G6 |
| notificationRetryLimit | 通知自动重试次数，不含首次发送 | 5 次 | 0—5 | G6 |

编辑和“填入默认值”均只改变表单，点击保存才提交。重试设为 0 合法，首次发送仍由通知服务负责。签到时点、固定两级审批、总截止或维修等固定规则不可在此修改。

409 会比较最新五项值与当前草稿。“采用最新配置”替换表单；“保留我的修改”以最新版本重新提交当前完整表单，须核对所有参数，不是自动合并部分字段。网络结果未知时冻结表单，手动重试沿用原正文和请求键；“重新读取配置核对”只查询状态，不表示取消原请求。

保存成功后再次读取当前配置，避免把缓存的原写响应当成最新状态。配置变更不撤销登录。数值完全相同时返回原版本/生效时间，不新增历史和空 ConfigChanged 事件，但仍记录幂等结果。

## 接口与存储

- P18 `GET /api/m2/admin/config`：管理员读取当前 Config，不接受查询参数。
- P19 `PATCH /api/m2/admin/config`：`{expectedVersion:"1",values:{五项完整整数值}}`。会话、CSRF、UUID 请求键必需；未知/缺失字段、错误类型和越界值拒绝。数学上为整数的 JSON 数值（如 2.0）规范化为整数 2。
- I07 `GET /internal/config`：G1/G2/G3/G4/G6 的独立服务凭据读取，不需要用户会话，不经前端公开。
- L03 `ConfigurationPort.current(): BusinessConfiguration`：G2 仅依赖 G5 api。返回当前版本、北京时间毫秒生效时刻和不可修改的 values，不读 G5 表。

成功信封 `{data:{version,effectiveAt,values},traceId}`，HTTP 读取 `Cache-Control:no-store`。无会话/权限明确拒绝；数据库或配置不可用 503，不用旧值或默认值兜底。默认值只由新迁移初始化一次，启动不覆盖已保存配置。

第 005 项迁移创建 `m2.business_config` 及 `m2.g5_config_history`。版本 1 为默认快照，actor 为 null；以后变更使用管理保护锁，检查当前操作者与 expectedVersion，递增版本、采用数据库时钟并保存完整快照和 actor。历史、当前值、ConfigChanged、outbox 和响应缓存同事务提交，失败整体回滚。

同用户/方法/路径/请求键的响应缓存 24 小时；同键同规范正文返回原结果，异正文 409。重放重新检查当前管理权限。更新不新增长期 creation_intent；缓存过期后旧 expectedVersion 按当前版本校验，重放不代表读取最新配置。

ConfigChanged 事件自带审计，source/aggregate 为 CONFIG，payload 含 configVersion、effectiveAt 和非空 changedKeys。审计保存完整五项前后值、版本和生效时间。事件保存于 G5 审计/outbox，集中发送及后台消费待 G6，不发送参数变更邮件。

## 后续消费约定

G1 新预约入口取得当前配置并冻结预约规则；G2 新整室申请保存配置版本并冻结 deadline；G6 首次受理通知冻结重试上限，下次扫描采用新间隔。已接受对象不追溯改写。各业务组提供自己的规则展示接口，失败不能用过期值冒充当前规则。

G5_SERVICE_G1/G2/G3/G4/G6_TOKEN 各自独立，32—256 字符；未配置拒绝，重复凭据阻止启动。新增 G2 凭据只允许 I07，不放行 I01；同服务 G2 优先用 L03。内网和网关隔离，前端子进程不继承这些秘密。

参数契约与 owner 交接见 [参数说明](../../contracts/config/g5/README.md)。旧 `/api/m1/admin/config`、`/api/m3/admin/config` 的兼容转发待 G6 真实网关集成，本轮不建立影子配置。

## 验证与恢复

```powershell
.\scripts\Test-G5Management.ps1 -EvidenceDirectory .local/g5-010/backend
.\scripts\Test-WebAuth.ps1
python scripts/verify-config-contracts.py
python scripts/verify-auth-contracts.py
```

后端分别使用四个全新隔离数据库，浏览器独立认证/管理数据库，不修改正常账号或配置。恢复旧参数应读取当前版本，经 P19 保存旧值产生新版本，不删除历史或改迁移。具体结果见验证记录，本文命令不等于已经通过。

实际结果：[本轮迁入验证](../../docs/verification/G5-MIGRATE-main-framework.md)。
