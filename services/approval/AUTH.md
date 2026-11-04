# G5 账户初始化与登录后端

本批提供账户数据库结构、六类内置角色、初始管理员、登录、身份查询、改密、退出，以及 G5-I01/G5-L01 会话校验。G5-003 已新增并接通 [认证前端](../../apps/web/README.md)；账户/院系管理后端及已有角色查询/绑定已由 [G5-007](MANAGEMENT.md) 提供，G5-008 已接入账户与院系管理页面，G5-009 已补充自定义角色及权限管理；房间范围实际联调和 G6 审计投递仍待后续任务。

## 数据与密码

登录用户名存放在 `m2.account.user_code`，数据库唯一；密码存放在 `password_hash`，内容为 Spring Security 的带随机盐 PBKDF2-HMAC-SHA256 散列，标记 `{pbkdf2@SpringSecurity_v5_8}`（310000 次迭代、16 字节盐、256 位派生密钥）。相同密码会生成不同散列，无法通过解密取回密码。

密码按照 Unicode 码点检查长度，保留完整 UTF-8，不截断前 72 字节，不去掉前后空格。新密码为 8—24 个字符，不能与当前密码相同，拒绝控制字符及不完整 Unicode。登录密码最长 128 个字符。密码、散列、会话及 CSRF 原文均不进入审计。

`m2.session` 保存随机会话与 CSRF 的 SHA-256 摘要、账户/权限版本和数据库生成的 8 小时绝对过期时刻。查询不会延长有效期；停用、撤销、版本变化或改密后旧会话失效。改密事务锁住账户，更新密码/账户版本并撤销该账户的全部会话。

## 本机首次初始化

从仓库根目录，在 PowerShell 中先读取数据库配置，再读取或生成本机管理员秘密：

```powershell
. .\scripts\Use-ApprovalLocal.ps1 `
  -CredentialFile 'F:\PostgreSQL\private\pj-dev-credential.xml' `
  -JdkHome "$PWD\.local\tools\jdk-21.0.13+9"
. .\scripts\Use-ApprovalAdmin.ps1
.\services\approval\mvnw.cmd -f .\services\approval\pom.xml -B -ntp verify
& "$env:JAVA_HOME\bin\java.exe" -jar .\services\approval\target\approval-service-0.1.0-SNAPSHOT.jar
```

首次管理员用户名为 `admin`。辅助脚本将随机初始密码以 Windows 加密 PSCredential 保存在 `.local/approval-admin-credential.xml`，并只给当前终端注入初始化变量。它不会把密码显示在构建输出中。需要本人查看时，可以在本机终端运行：

```powershell
(Import-Clixml .\.local\approval-admin-credential.xml).GetNetworkCredential().Password
```

查看结果是敏感信息，不要截图、录屏或复制到公开材料。改密后这个文件里的初始密码不再有效；再次运行初始化不会重置已修改密码。

2026-11-02 本机 admin 已按用户要求设置临时密码；随后 [G5-006 规则调整](../../docs/decisions/G5-006-password-policy.md) 取消首次强制改密，初始创建及主动改密改为 8—24 字符。已有凭据不重写，登录和当前密码仍保留 1—128 字符输入兼容，不提供跨环境通用默认密码。

不使用辅助脚本的环境可以通过 secret 提供 `G5_BOOTSTRAP_ENABLED=true`、`G5_BOOTSTRAP_ACCOUNT`（默认 admin）、`G5_BOOTSTRAP_PASSWORD`。初始化只在尚无账户时创建第一个管理员；已有同名账户时保留，已有其他账户时拒绝借改初始化用户名创建新管理员。完成初始化后建议撤下初始化变量。

初始管理员绑定系统管理角色及技术基线规定的全局运维动作，没有预约、审批或维修业务权限。六类内置角色的非系统默认范围为 SCHOOL，目前只有初始系统管理员被绑定；后续业务角色分配必须明确核对实际范围。种子不会覆盖已经修改的角色名、授权或账户绑定。

管理员的初始院系为合成 SYSTEM、联系邮箱为 `admin@example.invalid`，仅用于本地启动；实际联系资料须在账户管理能力建立后由授权人员维护，当前不会向这个占位邮箱发送邮件。

## 接口流程

完整字段和错误见 [OpenAPI](../../contracts/openapi/g5.yaml)，同进程端口见 [G5-L01](../../contracts/local/g5.md)。

1. `POST /api/m2/auth/login`，body 为 account/password。成功返回 `data.principal`、`data.csrfToken` 并设置 `PJ_SESSION` Cookie。
2. `GET /api/m2/auth/me` 带 Cookie 读取身份。登录后立即按角色返回实际 grants；mustChangePassword 为保留兼容字段，恒为 false，不拦截首次登录。
3. `POST /api/m2/auth/change-password` 带 Cookie、`X-CSRF-Token`，body 为 currentPassword/newPassword/expectedVersion；版本从 principal.accountVersion 取得，须为字符串。
4. 改密成功会清 Cookie，并撤销所有旧会话。使用新密码重新登录。
5. `POST /api/m2/auth/logout` 带 Cookie、CSRF 和空对象 `{}`；已无有效会话时仍成功。

Cookie 固定 Secure、HttpOnly、SameSite=Lax，Path=/，有效期 28800 秒，不使用 localStorage 保存会话。G5-003 前端通过同源 HTTPS 代理集成，另有真实浏览器验收；本批原有 Java HTTP 测试仍只证明后端行为，不能单独代替页面验收。

除当前接口定义字段外，未知 JSON 字段、查询参数和错误类型均拒绝。错误采用统一 code/message/traceId/details，身份响应不包含密码散列。返回禁止缓存。

账号和真实连接 IP 各自累计 5 分钟内 10 次失败后冷却 5 分钟；后续请求返回 429、Retry-After。限速持久保存在 PostgreSQL，进程重启不清空；不信任客户端任意 X-Forwarded-For。未来代理接入须与 G6 确认可信来源解析。

## 服务间校验和审计

`POST /internal/auth/introspect` 使用 Bearer 服务凭据，body 为 sessionToken。G1/G3/G4/G6 分别配置不同的 `G5_SERVICE_G1_TOKEN` 等秘密，长度 32—256。未配置服务不能调用，重复或不合法配置会阻止启动；G2 使用公开 `IdentityPort`。网关后续必须禁止公网访问 internal 路径。

成功/失败安全操作持久保存 AuditRecorded 事件与 G5 outbox，成功变更在同一事务提交。记录中只有动作、结果、对象、版本、时间和 trace 等允许字段；改密仅标记“已变更”。G5-013 已提供可靠投递与状态回查：未配置有效 G6 地址/凭据时标为 BLOCKED 并提示需处理；配置有效但暂时不可达时持久退避重试。只有验证受理回执后才记成功，不将本地入箱当作 G6 已集中接收；实际接入见 [运行端口说明](OPERATIONS.md)。

## 验证

`AuthFlowIT` 会修改测试账号、密码和限速记录，因此强制要求独立的 `pj_g5_auth_it_*` 数据库，拒绝普通 `pj_local`。当前完整套件包含认证、管理、配置、资源事实、业务授权和可靠投递，使用下面的辅助脚本创建六个专用库，不共享认证夹具。Windows 下先停止使用待打包 JAR 的服务，或在隔离源码副本运行，避免打包时文件被运行进程占用：

```powershell
.\scripts\Test-G5Management.ps1
```

首次种子验收需要新测试库；它不会自动清空已有账户，复跑完整验收应使用另一个专用空库。普通开发构建使用不带 profile 的 verify；只跑原骨架健康测试可添加 `-Dit.test=SkeletonHealthIT`。

验证包括密码散列与随机盐、首登直接使用、8/24 字符边界与 Unicode 密码、CSRF/版本/输入校验、所有旧会话撤销、停用/过期/版本撤销、持久限速、并发改密唯一成功、重启不重置种子及内部服务身份。当前结果见 [本轮迁入验证](../../docs/verification/G5-MIGRATE-main-framework.md)，早期结果保留在 G5-002。

接口样例为合成文档数据，不能替代真实行为测试。格式检查命令如下；可选 `--events` 参数传入脱敏的实际审计事件 JSON 数组：

```powershell
python -m pip install --target .local/contract-tools -r scripts/requirements-contracts.txt
python scripts/verify-auth-contracts.py
```
