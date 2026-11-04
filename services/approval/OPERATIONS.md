# G5 可靠投递与异常操作端口

G5-013 补齐技术 §5.6、§5.8、§7.4、§8.3 的 G5 提供端。只维护本组事件及操作状态，不实现 G2/G6 的聚合 HTTP 入口和运行页面。G6 真实消费待联调，测试接收服务不等于集中审计已经上线。

## 已提供的能力

| 能力 | 公开接口/实现 |
| --- | --- |
| G5-L04 查询 | OperationReadPort.list/get/metrics |
| G5-L05 恢复 | OperationRecoveryPort.retry |
| G5-L07 回查 | OutboxReadPort.get |
| 自动投递 | OutboxDispatcher → G6-I01 POST /internal/events |

调用方只引用 cn.edu.campus.approval.g5.api。完整 DTO 与请求见 contracts/local/g5-operations.md、g5-operations.schema.json 和 g5-operations.examples.json，原 G5 HTTP P01—P19/I01—I10 保持原路径。新增端口没有绕过公共会话/CSRF 的专用 HTTP 管理入口。

## 事件与操作

原业务事务写审计及 outbox，同事务触发器建立 g5_operation，任何一步失败整个修改回滚。六号前向迁移回填已有 PENDING/DELIVERED/BLOCKED 事件，不修改原事件内容、eventId、来源版本或密码。操作 ID 与本组 outbox 事件 ID 一致；公开 Operation.category=OTHER/eventId=null，事件关联通过 OutboxReference 提供。

投递器按当前 scanIntervalSeconds 扫描，1—30 秒；每轮预算十秒、最多五十次领取。短事务 FOR UPDATE SKIP LOCKED 领取，30 秒租约；提交后才 HTTP，五秒续租，返回后另开事务按 lease token + 未过期租约确认。两实例可同时扫描，旧 worker 不能覆盖新领取者。进程中断保留 RUNNING，租约到期重新发送原事件，未完成尝试明确 UNKNOWN。

每条尝试序号持久递增，重排或重启不清零。网络/503/畸形确认/丢响应按 5、15、30、60 秒及之后 60 秒重试；400/403/409/422 直接 BLOCKED。G6 对相同 eventId 去重，duplicate=true 同样可确认；G5 只据完整匹配的 200 Receipt 记 SUCCEEDED，不把任意 HTTP 200 当受理。

为覆盖最长三十秒扫描间隔，原受理后 270 秒仍未完成先标 needsAttention，保证五分钟内可见；失败尝试也检查等待时间。恢复不重置 acceptedAt/occurredAt 或历史积压年龄。持续不可达可 RETRY_WAIT 且需人工关注，不伪造已交付。自动失败不产生新的待投递失败审计，避免递归。

## 配置和受理确认

- G6_BASE_URL：仅 http/https origin，无路径/query/userinfo。
- G6_SERVICE_G5_TOKEN：G5→G6 专用 32—256 字符私有服务密钥，不是 G6→G5 调用密钥。
- G5_OUTBOX_ENABLED：默认 true。无有效地址/凭据时将待投递操作明确标 BLOCKED/DELIVERY_NOT_CONFIGURED，不尝试外网；配置修复后由有权限者重排。
- 显式 false 只暂停扫描，业务事务仍持久写事件与操作。重新启用需重启应用。

HTTP 连接两秒、总超时五秒，不自动跟随重定向、不持数据库事务、不发送 Cookie/X-Actor-Session。发送对象始终是原 G5 事件。

技术基线只命名 Receipt，未展开字段；G5 消费最小要求为外层 {data,traceId}、data={eventId,duplicate,acceptedAt}，schema 在 contracts/events/g5/delivery-receipt.schema.json。相同 eventId、布尔 duplicate 和合法受理时间全部满足才确认。允许提供端添加其他确认字段，但不能缺少最小事实。G6 提供组仍需评审此契约；未编写或修改 G6 接收业务。

## 权限、查询与重排

同服务入口负责对当前请求内省、CSRF 和请求键；不得缓存 Principal 跨请求。端口另从当前数据库复核账户启用、Principal 过期时间、账户/权限版本及实际 grants，防止使用陈旧或自造的 grants。logout 撤销会话由入口每次内省保证，Principal 型端口不接收原会话凭证。

G5 这些事件均 SYSTEM 范围；明确 SYSTEM operation:read 才能读列表、指标和 outbox 引用，房间授权不能扩大到系统。操作详情额外允许原 actor 查看自己的精简操作；system:manage 不是 operation:read/recover 的替代。禁用/无主体 401、版本变化 409、详情未知/不可见 404、动作不足 403。

列表先按权限过滤再计数/分页，默认最近七天，显式时间成对且不超过三十一天，createdAt 降序/id 升序。metrics 统计所有可见未终结操作及 outbox 的真实积压，notifications=null；最老等待按原 acceptedAt/occurredAt，不因重试变短。PendingRef 指向对应 G2/G6 聚合入口的目标路由，相关 HTTP 页面待消费组接入，不声称链接当前已可访问。L04/L07 不联网，可加入 G2 只读 REPEATABLE READ 快照。

重排要求 SYSTEM operation:read+operation:recover、expectedVersion、非空 reason 和原 Idempotency-Key。只允许 BLOCKED/RETRY_WAIT，成功、正在执行或已排队拒绝 STATE_CONFLICT；版本变化拒绝 VERSION_CONFLICT。同键同正文二十四小时内返回持久原响应，异正文 IDEMPOTENCY_CONFLICT；原响应过期后返回当前状态，不再次重排。HMAC 使用稳定私有密钥及字段排序后的正文；不保存凭证。当前鉴权优先于幂等回放。

重排、幂等结果和 G5-L05 恢复审计同事务，审计关联原 operationId；失败不留半次重排。原事件/来源版本不变，账户、角色、配置不再执行。成功投递只代表 G6 接收事件，不代表邮件发送或收件人已读。

## 验证和交付

scripts/Test-G5Management.ps1 新增第六个独立数据库 G5_OUTBOX_TEST_DB_URL；可加 -TestPattern ReliableOutboxFlowIT 定向验证。scripts/verify-operation-contracts.py 校验五项端口样例及实际 DTO；原认证/配置校验脚本继续使用。

实际运行、测试限制、源码/制品摘要见 docs/verification/G5-MIGRATE-main-framework.md。六号迁移已在本地执行后不得删除/改校验和；回退应用时停投递器，保留操作/尝试和事件，不通过删除历史回退。

后续需 G2 接入运行聚合、G6 确认 Receipt 并接收真实事件，消费组评审同服务契约及迁移，按仓库流程人工验收后提交。本批没有自动推送、合并或生产发布。
