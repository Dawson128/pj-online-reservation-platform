# G5 院系与账户管理接口

当前实现 P05—P17，沿用审批服务和 m2 中的 G5 表。本文件说明后端和契约；G5-008 已接入账户/院系管理页面，G5-009 补充角色与权限管理；G5-010 已提供[全局参数](CONFIGURATION.md)，房间资格接口仍待后续批次。

## 已实现接口

| 接口 | 方法与路径 | 行为 |
| --- | --- | --- |
| P05/P06 | GET /api/m2/admin/accounts、/{id} | 搜索、筛选、分页和账户详情，不返回密码或散列 |
| P07 | POST /api/m2/admin/accounts | 创建账户并绑定院系、已有角色，新密码8—24字符 |
| P08 | PATCH /api/m2/admin/accounts/{id} | 修改姓名、院系、邮箱或启停状态，不能修改用户编号和密码 |
| P09/P10 | GET /api/m2/admin/roles、/{id} | 查询已有角色及动作、范围 |
| P11/P12 | POST /api/m2/admin/roles、PATCH /{id} | 新增自定义角色，编辑名称和完整授权；编号/内置标识不可更改 |
| P14 | GET /api/m2/admin/permissions | 返回 38 个封闭动作的中文说明、支持范围及所属模块 |
| P13 | PUT /api/m2/admin/accounts/{id}/roles | 完整替换角色绑定，角色ID去重，最多50项 |
| P15 | GET /api/m2/departments | 所有有效登录账户可读，默认启用院系；enabled=false查询停用记录 |
| P16/P17 | POST /api/m2/admin/departments、PATCH /{id} | 新增、改名、启停；院系编号不可改 |

完整字段和响应见 [G5 OpenAPI](../../contracts/openapi/g5.yaml) 与 [管理样例](../../contracts/openapi/examples/g5-management.json)。除院系列表外均要求当前 system:manage 和 SYSTEM 范围；普通业务角色直接请求管理接口也会拒绝。

## 写入和一致性

写请求携带会话 Cookie、X-CSRF-Token、UUID格式 Idempotency-Key，更新携带字符串 expectedVersion。未知字段、重复/空查询参数和错误类型均拒绝；鉴权先于输入校验。

管理写事务先锁 G5 管理保护行，再检查当前操作者资格。账户资料、状态、角色变化递增账户和权限版本，撤销目标的全部旧会话。最后管理员按有效账户及实际 SYSTEM/system:manage 授权判断，不只看角色名称；并发停用不能使其数量变为零。

院系停用禁止新的院系绑定，既有账户、角色和历史保留。账户修改其他资料时可以继续保留原来已停用的院系。

完整无密码响应缓存24小时，缓存内重放返回原状态和JSON内容，字段顺序不构成差异。过期后创建意图仍识别同一操作者、方法、路径和请求键，同摘要读取当前对象并返回200，不重建账户或覆盖已改密码；同键异内容409 IDEMPOTENCY_CONFLICT。重放仍须通过当前权限核验。

管理请求只保存服务密钥HMAC摘要。G5_IDEMPOTENCY_HMAC_KEY须为32—256字符的稳定私有密钥，跨重启保留；未配置时管理写返回503 AUTH_UNAVAILABLE。Windows本机由 [辅助脚本](../../scripts/Use-ApprovalAdmin.ps1) 生成并加载 `.local/g5-idempotency-credential.xml`，前端子进程不继承该密钥。

成功变更、脱敏审计和outbox同事务提交；不在审计中保存邮箱、密码或散列。G6集中投递仍待接入。

## 本机验证账号

启动 [本机网站](../../apps/web/README.md) 后，从仓库根目录执行：

```powershell
.\scripts\Initialize-G5DemoAccounts.ps1
```

脚本经固定本机HTTPS接口创建两个合成院系和七个验证账号，保留已有admin。加上admin，覆盖六类角色；包含两院系学生和一个停用账户。

应用户要求，初始测试密码写入本机 `.local/验证账号.md`。该文档、加密凭据和稳定请求键均被Git忽略，代码库不保存实际测试密码。重跑不覆盖账户、角色或密码，用户自行改密后以新密码为准。这些账户不会自动导入生产环境。

## 验证

```powershell
.\scripts\Test-G5Management.ps1
```

脚本建立四个独立的pj_g5_auth_it_*数据库，分别隔离认证/健康、账户角色管理、配置和 G4 事实夹具，不修改pj_local密码。直接运行Maven的postgres-it时，除DB_URL外须提供独立的G5_MANAGEMENT_TEST_DB_URL、G5_CONFIG_TEST_DB_URL和G5_RESOURCE_FACTS_TEST_DB_URL；普通verify只构建。

实际结果见 [本轮迁入验证](../../docs/verification/G5-MIGRATE-main-framework.md)，涵盖增查改停、分页、角色与会话撤销、权限优先、幂等/过期重放、重启、outbox故障回滚及并发最后管理员保护。


## 角色与权限（G5-009）

管理员从顶部“角色与权限”进入 /admin/roles。选择目录中的操作，再选择全校、指定院系、指定房间或系统范围；每个动作只展示契约支持的范围。允许空授权角色，也可为同一操作添加多个范围。内置角色可改名称和 grants，不提供删除或改 key/builtIn；创建只产生 builtIn=false 的自定义角色。

所有绑定账户在角色更新事务内递增账户/权限版本并撤销全部旧会话；修改本人绑定的角色后前端返回登录。最后一个有效 SYSTEM/system:manage 管理员不能通过修改角色间接撤销，保护与账户启停/绑定共用管理保护锁。失败事务回滚角色、授权、版本、会话撤销、审计及幂等结果。

授权最多 1000 条，严格校验嵌套字段、动作与范围，去重并规范排序；同内容不同授权顺序不构成不同请求，跨重启摘要稳定。修改要求 expectedVersion，409 保留前端草稿并展示当前授权。创建同键响应缓存过期后仍返回原角色当前详情。原成功结果重放不重新请求 G4，但必须通过当前管理员鉴权。

院系由 G5 核验存在，已停用字典项以明确标签显示；账户新院系绑定仍禁止停用项。房间由 G5 在数据库事务/锁外调用 G4-I02（AUTHORIZATION + traceId），使用独立 G4_SERVICE_G5_TOKEN 和仅 origin 的 G4_BASE_URL。连接超时 2 秒，所有房间查询共用 5 秒预算；不携带用户会话，不自动重试或重定向。未知房间映射 400；未配置、超时、错误响应及依赖不可用返回 503 SCOPE_FACTS_UNAVAILABLE，不保存未经核验的范围。

本仓库尚无真实 G4 服务。全校/院系/系统授权可用，房间范围完整联调仍待 G4 提供服务；隔离测试中的 HTTP 边界替身只验证消费者协议和故障路径。权限配置也不代表其他组预约、审批或维修业务已经开放。

角色审计采用 SYSTEM 来源的角色 ID，记录 key/name/version 和授权数量、完整授权 JSON 的 SHA-256 摘要，遵守 AuditField 长度限制；不将密码、会话或服务凭据写入事件。集中投递仍待 G6 接入。

角色与权限本轮实际结果见 [本轮迁入验证](../../docs/verification/G5-MIGRATE-main-framework.md)。
