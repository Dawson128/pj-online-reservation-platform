# G5 提供给 G4 的院系与账户资格事实

本批实现技术文档 v2 的 G5-I08、G5-I09，均为审批服务中的 G5 内部接口。实际 G4 服务及资源影响业务尚未接入；当前验证的是 G5 提供端，不代表已完成房间归属变更、预览、取消或资源事务联调。

## 调用身份

调用方使用私有 `G5_SERVICE_G4_TOKEN`，以唯一 `Authorization: Bearer <私有凭据>` 提交。G5 从实际凭据识别 G4，不信任 actorUserId、用户 Cookie 或自称 G4 的请求头。G1/G2/G3/G6 的有效服务凭据也不能调用这两项接口。缺少、错误或重复 Authorization 返回 403；鉴权先于参数校验。

前端代理不开放 `/internal`。这两项请求不需要用户会话、CSRF 或请求键。G4 凭据缺省时拒绝；不得放入前端环境、版本库、日志、operation 或 outbox。正式部署继续由网关/内网隔离内部服务。

G5 身份过滤与控制器映射使用相同的容器解码路径，编码后的 internal/api 前缀或 departments 路径不能绕过身份及调用组限制。既有 I01 仍限 G1/G3/G4/G6，I07 仍供 G1/G2/G3/G4/G6；没有扩大 G2 的其他访问能力。

## I08 院系详情

```http
GET /internal/departments/{id}
Authorization: Bearer <G4 服务私有凭据>
```

路径为标准小写 UUID，不接受查询参数或请求正文。成功返回标准信封：

```json
{
  "data": {
    "id": "00000000-0000-4000-8000-000000000090",
    "version": "2",
    "createdAt": "2026-11-03T12:00:00.000+08:00",
    "updatedAt": "2026-11-03T12:05:00.000+08:00",
    "code": "SAMPLE",
    "name": "示例院系",
    "enabled": false
  },
  "traceId": "00000000-0000-4000-8000-000000000099"
}
```

已停用的院系仍是存在的历史字典记录，返回 200 与 enabled=false，不假装不存在；G4 根据自己的创建/归属变更规则决定能否新选。未知院系 404，格式错误 400。仅返回院系，无所属账户列表或联系方式。响应 traceId 由 G5 生成。

## I09 账户资格事实

```http
POST /internal/accounts/qualification-facts
Authorization: Bearer <G4 服务私有凭据>
Content-Type: application/json
```

完整请求示例（全部 ID 为合成示例）：

```json
{
  "purpose": "RESOURCE_IMPACT",
  "roomId": "00000000-0000-4000-8000-000000000010",
  "userIds": ["00000000-0000-4000-8000-000000000001"],
  "traceId": "00000000-0000-4000-8000-000000000099"
}
```

仅支持 RESOURCE_IMPACT，四项必填且不接受额外字段、查询参数。userIds 为 1—100 个标准小写 UUID，数量按原数组计算；结果与输入逐项对应，包含重复 ID 的对应位置。G4 协调器仍应按自己的契约排序去重并分批，G5 不擅自改变输入顺序。

```json
{
  "data": {
    "items": [
      {
        "userId": "00000000-0000-4000-8000-000000000001",
        "departmentId": "00000000-0000-4000-8000-000000000090",
        "enabled": false,
        "accountVersion": "3"
      }
    ],
    "checkedAt": "2026-11-03T12:05:00.000+08:00",
    "validUntil": "2026-11-03T12:05:05.000+08:00"
  },
  "traceId": "00000000-0000-4000-8000-000000000099"
}
```

停用账户返回 enabled=false；不存在任一账户时整批 404，错误信封中没有 data 或部分 items。数据仅含当前 userId、departmentId、enabled、accountVersion，不读出密码/邮箱/姓名/角色再过滤，不返回旧院系快照。合法请求 traceId 用于响应及 X-Trace-Id，并用于后续读取拒绝审计；非法身份及尚未通过 traceId 校验的请求使用 G5 自己的追踪编号。

同一次只读事务用一条批量 SQL 读取账户一致快照，再用数据库时钟记录 checkedAt，validUntil 精确为 checkedAt+5 秒，输出北京时间毫秒。每次重新读取，不跨请求缓存；有效期不表示账户在这 5 秒内绝不变化，也不是房间动作授权。

G5 只校验 roomId 格式；不回调 G4 核验房间存在，不判断是否已受理资源操作。G4 必须把调用绑定到已受理的空间变更，并自行使用返回的当前事实判断影响。G5 不代替 G4 决定取消预约/申请，不返回 room access 或 canAccessRoom 结论。

## 失败与消费责任

| 情况 | 结果 |
| --- | --- |
| 非 G4、缺少/错误/重复服务身份 | 403 FORBIDDEN |
| 非法 UUID、目的、字段、数组长度或查询参数 | 400 VALIDATION_ERROR |
| 未知院系或任一未知账户 | 404 NOT_FOUND，整次没有事实结果 |
| 当前读取故障 | 503 DEPENDENCY_UNAVAILABLE，不回旧值/空列表 |
| 故障同时使安全审计无法保存 | 既有 503 AUTH_UNAVAILABLE，仍不可使用事实 |

成功响应 Cache-Control:no-store。G4 需要在事务外准备事实，持房间锁后校验版本/有效期；过期时结束当前事务并在锁外重取，不能锁内联网。多批查询须以最早 validUntil 判断，不使用不完整或过期事实。来源对象及其当前 owner、房间版本、影响摘要和实际取消均由 G4/相应业务组处理。

没有新增表、迁移、用户权限或页面；成功查询没有业务事件或数据写入，安全失败沿用 G5 脱敏审计。详细 DTO 和错误见 [OpenAPI](../../contracts/openapi/g5.yaml) 及 [完整样例](../../contracts/openapi/examples/g5-resource-facts.json)。

## 验证入口

```powershell
.\scripts\Test-G5Management.ps1 -EvidenceDirectory .local/g5-011/backend
.\scripts\Test-WebAuth.ps1 -SkipBuild
python scripts/verify-auth-contracts.py
```

后端分别隔离认证/健康、账户角色管理、配置和 G4 事实四组数据库。事实测试的 SQL 仅用于批量读取夹具、原子并发更新和隔离故障；调用方身份、接口校验和返回均经真实打包服务与 HTTP。网站回归使用独立数据库，验证私有路径边界；正常开发账号和配置不做故障注入。

实际结果：[本轮迁入验证](../../docs/verification/G5-MIGRATE-main-framework.md)。
