# G5 业务授权与通知收件

技术基线 §4.2、§5.7—5.8、§8。提供端实现由 G5-012 交付；真实 G1—G4/G6 消费者未接入。测试中的 G4 为受控 HTTP 服务，不代表空间数据已建立。

## 接口与调用身份

| 接口 | 允许调用方 | 用途 |
| --- | --- | --- |
| I10 POST /internal/authorization/check | G1/G3/G4/G6 + X-Actor-Session | 按当前会话、版本及动作/目标批量判断 |
| I02 POST /internal/authorization/eligibility | G2/G4 FINAL_APPROVAL；G3 REPAIR_START | 独立后台资格，不依赖原操作人的会话 |
| I03 POST /internal/maintenance/assignee-candidates | G3 + X-Actor-Session | 先验派单权限，再筛选、计数和分页 |
| I04 POST /internal/maintenance/assignee-eligibility | G3 + X-Actor-Session | 派单操作者和目标维修人分开核验 |
| I05 POST /internal/notification-recipients/query | G6，NOTIFICATION_ROUTING | 完整事件的当前收件策略 |
| I06 POST /internal/notification-recipients/validate | G6 | 每封发送前重算完整策略 |

服务凭据通过 G5_SERVICE_G1/G2/G3/G4/G6_TOKEN 分别注入，各不相同，32—256 字符。只接受唯一 Authorization: Bearer 请求头；代用户接口另须唯一 X-Actor-Session，不接受 Cookie 代替。用户会话只在请求内存中流转，不写日志、operation 或 outbox。前端入口不代理 internal。

完整请求与成功/失败样例见 contracts/openapi/g5.yaml、contracts/openapi/examples/g5-business-authorization.json。UUID/版本输入为字符串，字段闭合；未知字段、动作、重复 checkId、空/超限批次拒绝。I10 每次 1—100 项；按 checkId 原顺序对应返回，不返回部分结果。

## 范围和资格

多角色取动作与范围的并集，不从角色名称推断能力。SCHOOL 匹配所有已登记房间；DEPARTMENT 只匹配同院系且非校级的房间；ROOM 精确匹配；SYSTEM 只匹配系统目标。仅 SYSTEM_ADMIN 不自动得到预约、审批或维修权；初始管理员明确持有的 SYSTEM 运维权限仍有效。

room:read、reservation:create、room:apply、workorder:report 另检查普通院系访问（全校开放或账户院系相同）；历史本人动作和管理动作按当前 grant 范围判断，不用当前账户院系替代授权。本人归属、审批同人回避、验收实际维修回避仍由业务 owner 检查。房间 enabled 是 G4 的资源可用性事实，G5 不因此拒绝历史范围或维修资格。

FINAL_APPROVAL 固定 room:apply + WHOLE，要求当前账户启用、动作、范围和院系访问全部成立。REPAIR_START 固定 workorder:repair + REPAIR，允许有明确范围的跨院系维修。否定资格是 200/eligible=false；未知账户 404；消费者按自身业务契约映射拒绝。

I03 keyword 匹配 userCode/displayName，但返回 Candidate 仅 userId/displayName/qualificationSummary，不泄露邮箱。先筛有效维修资格再 total/page，默认 createdAt 降序/id 升序。I04 先验证操作者，再查询目标，目标不能借用操作者的权限。

## 房间事实与事务

配置 G4_BASE_URL（仅 http/https origin，无路径/凭据/query）和 G4_SERVICE_G5_TOKEN。G5 调用 G4-I08 取得 I10 的批量事实；其他接口调用 G4-I02 单房间事实。仅发送 G5 服务凭据，不回传用户会话；通知请求附 NOTIFICATION_ROUTING/eventType/eventId/traceId，授权请求为 AUTHORIZATION。连接 2 秒、请求最多 5 秒，无重试、重定向或跨请求正向缓存。

G4-I08 每个唯一房间必须有且只有一项，未知事实为 null；缺失、重复、错误字段或错误响应整次 503。未知房间在 I10 为 RESOURCE_NOT_FOUND 决定，其他房间事实接口为 404。依赖失败不默认放行、不返回空冒充成功。

远程调用在数据库事务外。拿到房间事实后，在 G5 只读 REPEATABLE READ 快照内重新检查会话及账户/权限版本，防止准备过程中撤权后仍沿用旧事实。资格及授权 checkedAt 使用数据库时钟，validUntil 精确加 5 秒。消费方持锁后若事实过期，回滚并在事务外重取；G5 不保证跨服务全局原子撤权。

## 通知策略

接收完整 19 类事件，按闭合 schema、producer、source、信封/audit 关联校验；不接受自由邮箱、URL 或任意收件人列表。G6 负责只转交其已受理原事件，G5 不独立证明远端 inbox 已接受。消费基线 schema/注册表在 contracts/events/g5，提供组评审尚待完成。

固定参与人来自事件快照；管理分支按当前动作与范围。升级要求同一房间同时匹配 assign+read 或 escalation-receive+read，动作可来自不同角色。一级审批排除申请人，二级排除申请人及一级审批人；完工通知的管理员分支排除全部实际维修人。报修人分支独立保留。

维修开始、验收通过仅 REPORTER；完工提交 REPORTER + ROOM_MANAGER；改派新旧维修人并集；返回/关闭为报修人及事件快照维修人；恢复事项按房间 operation:recover 或明确 SYSTEM 权限。其余逐项样例见 notification-events.json。ConfigChanged、ClaimInvalidated、MaintenanceImpactApplied、AuditRecorded 不发邮件。

按 userId 去重并合并 branches，两人共用邮箱仍是两名收件人。停用、无有效邮箱和明确回避返回 excluded；参与人不因管理员授权消失而被丢弃。不枚举所有无关或越范围管理人员。任一必需房间/账户事实不可用整次失败，包括 I06，不能先发已知参与人。recipients 或 excluded 超过契约 1000 上限整次 400，不截断。空集合明确 NO_ELIGIBLE_RECIPIENTS。

G6 仍负责 SMTP、投递去重/重试和 G1 提醒当前状态检查。I06 仅短时事实，不能消除资格核验到 SMTP 之间的竞态。

## 本地端口与验证

L01/L02：注入 api.IdentityPort；introspect(sessionToken) 和 eligibility(input)。L02 只允许 G2 FINAL_APPROVAL。L06：注入 api.AuthorizationPort；check(sessionToken,input)，另提供带 traceId 重载。所有输入沿用 HTTP 字段与字符串 UUID，返回 BusinessFacts 中的公开只读 DTO。L02/L06 可能联网，调用方已开启事务时立即拒绝，不偷偷挂起事务联网。G2 不能引用 G5 repository 或查 G5 表。

验证入口：scripts/Test-G5Management.ps1 -EvidenceDirectory .local/g5-012/backend，创建六个独立 PostgreSQL 测试数据库。可用 -TestPattern BusinessAuthorizationFlowIT 单测新增案例。契约运行 scripts/verify-auth-contracts.py 与 scripts/verify-config-contracts.py。实际执行及候选制品摘要见 docs/verification/G5-MIGRATE-main-framework.md。

本批不新增页面、业务表、迁移或正常账号授权。异常操作列表/恢复 L04/L05、G6 集中投递及真实跨组调用仍待后续，不把本批解释为全部 G5/整个平台完成。
