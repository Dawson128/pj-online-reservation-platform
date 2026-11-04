# 审批服务（main 框架 + G5）

G2/G5 共用一个后端部署单元。当前实现 G5 账户、会话、院系、角色权限、全局参数及房间授权/资格/通知收件逻辑。详细使用方式见 [认证说明](AUTH.md)，真实页面见 [前端说明](../../apps/web/README.md)。业务授权接口见 [接入说明](BUSINESS_AUTHORIZATION.md)。真实业务消费者及 G6 审计投递仍待后续任务。

## 工程结构

```text
services/approval/
  pom.xml                  继承根 cn.edu.campus 父工程
  mvnw / mvnw.cmd          Maven 3.9.11 构建入口
  .mvn/wrapper/            下载地址与 SHA-256 校验
  src/main/                唯一应用入口、公共启动与配置
  modules/g5/              G5 api/application/domain/persistence
db/m2/                     统一打包的 G2/G5 迁移目录
```

G2 后续在 `modules/g2` 添加模块并由服务根构建装配。服务装配由 G6 协调，业务规则留在各自模块中。

## 前提与版本

- JDK 21。本次 Windows 验证使用 Temurin 21.0.13+9。
- Spring Boot 4.1.2，依赖版本由其 BOM 管理。
- Maven Wrapper 固定 Maven 3.9.11，无需全局安装 Maven。首次构建需要连接 Maven Central 下载工具及依赖。
- 可连接的 PostgreSQL 17；本次本地验证使用 17.11。

本机临时下载的 JDK 和构建缓存位于根目录 `.local/`，不会进入 Git。其他成员需要自行准备 JDK 21。

## 配置

启动程序前，在同一个终端设置环境变量。`.env.example` 仅供参考，程序不会自动读取 `.env` 文件。

| 变量 | 含义与默认值 |
| --- | --- |
| `DB_URL` | 兼容别名；默认 `jdbc:postgresql://127.0.0.1:5432/campus?currentSchema=m2` |
| `DB_USERNAME` / `DB_PASSWORD` | 兼容别名；运行用户默认 app_m2，密码必填 |
| `DB_MIGRATION_URL` | 可选，迁移连接，默认与 `DB_URL` 相同 |
| `DB_MIGRATION_USERNAME` / `DB_MIGRATION_PASSWORD` | 兼容别名；迁移用户默认 migrate_m2，密码必填 |
| `APP_BIND_ADDRESS` | 默认 `127.0.0.1` |
| `APP_PORT` | 默认 `8082` |
| `MANAGEMENT_PORT` | 默认 `18082`，始终只监听 `127.0.0.1` |

正式测试/生产环境应提供独立迁移账户和仅有必要 DML 权限的运行账户。已有本机 `pj_dev` 为开发库所有者，可以暂时同时用于本地验证，不代表正式权限隔离已经完成。

### 当前 Windows 本机

以下命令从仓库根目录运行，使用此前安装 PostgreSQL 时保存的 Windows 加密凭据。脚本仅设置当前 PowerShell 进程环境变量，不打印或重新保存密码。

```powershell
. .\scripts\Use-ApprovalLocal.ps1 `
  -CredentialFile 'F:\PostgreSQL\private\pj-dev-credential.xml' `
  -JdkHome "$PWD\.local\tools\jdk-21.0.13+9"
```

路径不同的机器应调整两个参数。已有 JDK 21 和正确 `JAVA_HOME` 时可以省略 `-JdkHome`。

### 其他环境

使用部署平台的 secret 设置上述变量。本地也可以通过交互方式读取密码，避免在终端历史中直接写密码正文，例如：

```powershell
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:5432/pj_local'
$approvalRuntime = Get-Credential -Message 'Runtime database account'
$env:DB_USERNAME = $approvalRuntime.UserName
$env:DB_PASSWORD = $approvalRuntime.GetNetworkCredential().Password
$approvalMigration = Get-Credential -Message 'Migration database account'
$env:DB_MIGRATION_USERNAME = $approvalMigration.UserName
$env:DB_MIGRATION_PASSWORD = $approvalMigration.GetNetworkCredential().Password
Remove-Variable approvalRuntime,approvalMigration
```

## 构建与启动

从仓库根目录，在已经配置环境的终端执行。Windows 下须先停止使用该 JAR 的本机服务，再构建；可用 `scripts/Stop-CampusLocal.ps1` 停止本启动器管理的进程。需要保留预览运行时，在隔离源码副本中构建，避免覆盖正在运行的 JAR。

```powershell
.\services\approval\mvnw.cmd -f .\services\approval\pom.xml -B -ntp clean verify
& "$env:JAVA_HOME\bin\java.exe" -jar .\services\approval\target\approval-service-0.1.0-SNAPSHOT.jar
```

第一条编译并打包工程。真实数据库集成测试需显式启用下面的 `postgres-it` profile；普通 `verify` 成功不代表数据库测试已经运行。第二条以前台进程启动服务，按 Ctrl+C 停止，再次执行同一条命令即可重启。

Linux/macOS 在设置环境变量后，从 `services/approval` 运行 `sh ./mvnw -B -ntp clean verify`，再运行 `java -jar target/approval-service-0.1.0-SNAPSHOT.jar`。本次只实际验证 Windows 命令。

Flyway 启动时使用迁移账户统一扫描 `db/m2`，建立 `m2` schema、历史表和 G5 账户/会话等结构。角色/权限种子可重复执行；管理员只在显式启用私有初始化配置时创建，见认证说明。数据库不可达、迁移失败或必需配置缺失时，不应视为成功启动。

账户/院系后端及验证账号见 [管理说明](MANAGEMENT.md)。当前完整 PostgreSQL 验收使用六个隔离数据库，可从仓库根目录运行 `scripts/Test-G5Management.ps1`。脚本创建认证、管理、配置、资源事实、业务授权和可靠投递六组独立夹具；直接运行 postgres-it 时，除认证库的 DB_URL 外，还须提供各自独立的 G5_MANAGEMENT_TEST_DB_URL、G5_CONFIG_TEST_DB_URL、G5_RESOURCE_FACTS_TEST_DB_URL、G5_BUSINESS_TEST_DB_URL 和 G5_OUTBOX_TEST_DB_URL。

## 检查运行结果

服务启动后，在另一个终端执行：

```powershell
Invoke-RestMethod http://127.0.0.1:18082/actuator/health
Invoke-RestMethod http://127.0.0.1:18082/actuator/health/readiness
Invoke-RestMethod http://127.0.0.1:18082/actuator/health/liveness
```

正常均为 HTTP 200，`status` 为 `UP`；readiness/liveness 内容为 `{"status":"UP"}`，总 health 另含 `groups:["liveness","readiness"]`。数据库运行中断连时，health/readiness 应为 HTTP 503 和 `DOWN`；liveness 仍为 200/UP，表示 Java 进程仍存活。数据库恢复后，原进程应恢复为 ready。

业务端口 8082 提供认证接口；根路径不提供前端页面，请访问前端预览地址。Actuator 不在业务端口开放；管理端口只开放 health，不开放 env、beans、mappings，也不返回连接串及凭据。

这些探针是本机工程检查。它们尚未实现技术文档 G6-I02b 所需的服务鉴权、RESOURCE 等完整就绪检查，不能作为最终生产发布门禁。

## 真实数据库集成测试

完整验收会修改测试账户和密码，必须使用独立 `pj_g5_auth_it_*` 空数据库，见 [认证测试要求](AUTH.md)。在配置好该测试库环境变量的终端，从仓库根目录运行：

```powershell
.\services\approval\mvnw.cmd -f .\services\approval\pom.xml -B -ntp -Ppostgres-it verify
```

测试运行打包后的真实应用及真实 PostgreSQL，验证：

1. 正常数据库下 health/readiness/liveness 的状态与管理接口边界。
2. 仅切断测试应用的数据库连接路径后，readiness/health 为 503/DOWN，liveness 仍正常；恢复路径后原进程重新 ready。
3. 停止后用同一 JAR 再次启动。
4. 错误迁移密码、缺少运行密码时启动失败。

数据库故障由测试内 TCP 转发器制造，不停止共享 PostgreSQL。测试需要能创建 schema/迁移历史表的开发或隔离测试库；不要把生产库连接交给此测试。缺少必需环境变量将明确失败，不会跳过后宣称通过。

上述骨架场景与 AuthFlowIT 的登录验收一同执行。只跑骨架可在命令中增加 `-Dit.test=SkeletonHealthIT`。结果位于 `services/approval/target/failsafe-reports/`；测试应用日志位于同一 `target` 下。生成文件被忽略，脱敏结论写入验证记录。

## 常见问题

- `JAVA_HOME` 无效或 Java 版本不符：检查 JDK 21 及 `java -version`，当前进程环境不会自动应用到其他已打开终端。
- 首次依赖下载失败：检查网络和 Maven Central 连接，修复后重试；不绕过 Wrapper 的校验和。
- 数据库认证失败：核对当前终端中的运行与迁移变量，避免把系统登录账号和数据库账号混淆。
- Flyway 初始化失败：确认库可连接且迁移账户能创建/维护 m2；不要开启 baseline-on-migrate、outOfOrder 或 clean 来掩盖问题。
- 端口占用：停止原应用，或设置不同的 `APP_PORT` 和 `MANAGEMENT_PORT`。

本批范围与实际验证分别见 [任务单](../../docs/tasks/G5-MIGRATE-main-framework.md) 和 [本轮迁入验证](../../docs/verification/G5-MIGRATE-main-framework.md)。

全局参数、配置服务读取和后续业务消费约定见 [配置说明](CONFIGURATION.md)。

G4 所需 I08/I09 的服务身份、最小事实、有效期与消费责任见 [事实接口说明](RESOURCE_FACTS.md)。

G5 自身可靠事件投递及 L04/L05/L07 见 [运行与恢复说明](OPERATIONS.md)；G6 地址/服务密钥缺失时明确待人工配置，不影响原同步管理操作成功，但不能视为集中审计已接收。

main 标准配置 DATABASE_URL / DATABASE_USERNAME / DATABASE_PASSWORD、MIGRATION_DATABASE_URL / MIGRATION_USERNAME / MIGRATION_PASSWORD、SERVER_ADDRESS / PORT 优先于上述 G5 旧别名。本机 Use-ApprovalLocal.ps1 显式选择旧别名并清除当前进程的这些标准变量，防止隔离库被继承的环境覆盖。管理端口正常模式独立，shell 模式仅装配，绝不能用于业务验收。
