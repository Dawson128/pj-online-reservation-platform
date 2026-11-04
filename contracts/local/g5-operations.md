# G5-L04/L05/L07（v0.8 本地契约）

公开 Java 包 cn.edu.campus.approval.g5.api。OperationReadPort.list(Principal,Map query)/get(Principal,UUID)/metrics(Principal,UUID|null)，OperationRecoveryPort.retry(Principal,UUID,Map reasonBody,UUID key)，OutboxReadPort.get(Principal,UUID)。结果为 OperationFacts 对应 record，字段完全沿用技术 §4.3、§5.6。UUID 参数使用 Java UUID，query 的 ID/时间/版本为 wire 字符串，page/pageSize 为 Integer。reasonBody={expectedVersion,reason}，key 是原 Idempotency-Key。

每次从 G5 当前账户和 grants 复核提供的 Principal；不信任调用方自行拼出的 grants。禁用/不存在主体401，账户/权限版本变化409。L04 list/metrics 与 L07 需要 operation:read 对应范围；详情另允许原 actor 本人读取自己的精简操作。重试需要 operation:read+operation:recover，前端的 system:manage 不自动满足。全部 G5 事件是 SYSTEM，房间授权不能看到 SYSTEM 事件。未知/不可见详情404，显式越权roomId403，合法无数据200空。

list query 为技术 §5.6 b 的全部可选字段，ownerGroup若给定只能 G5；默认最近7天，createdFrom/createdTo须成对且最大31天；createdAt降序/id升序，先按权限过滤再count/page。G5 category=OTHER、phase=NONE、eventId=null（被投递事件ID仅通过outbox引用查询）。原等待起点不因重试重置，metrics notifications=null。

L04/L07 加入 G2 只读 REPEATABLE READ 事务，无网络。L05 由同服务入口完成CSRF/用户会话/Idempotency-Key，当前权限仍在端口重查；端口自身数据库事务保存重排/幂等结果/恢复审计，不联网。BLOCKED/RETRY_WAIT方可重排，SUCCEEDED/RUNNING/PENDING拒绝409 STATE_CONFLICT，expectedVersion不符409 VERSION_CONFLICT；24小时内同键同正文重放原结果，异正文409，缓存过期同键返回当前状态不重复重排。重排只针对原事件，不重做账号/角色/参数修改。

调用 G6-I01 的最小消费确认在 delivery-receipt.schema.json，技术基线未展开 Receipt，此要求待 G6 提供者确认。200+相同eventId+duplicate布尔+合法acceptedAt才确认，外层需data/traceId；坏确认按结果未知重投原eventId。不得从任意200或HTTP409推断受理。

此契约只有 G5 提供端，不新增/冒充 G2-P08 或 G6-I03 HTTP聚合端点，不修改其他owner表。消费者集成评审待完成。
