# 数据库建表与迁移指南

给首次运行项目、继续开发 G5 或新增其他模块数据表的开发者。依据 [技术文档](../需求与技术文档/技术文档v2.md) §2.2、§3、§10.2 和 [仓库规则](../AGENTS.md) §5 编写。

**现有表由 Flyway 迁移脚本创建。新成员准备好空数据库、连接凭据并启动对应服务后，Flyway 会执行尚未执行的迁移；不需要根据本文手工逐张建表。** 新增表或修改字段时，提交新的版本化 SQL，让其他成员与部署环境得到同样的结构。

## 1. 先确定表属于谁

| 服务 | schema | 模块所有者 | 迁移目录 |
| --- | --- | --- | --- |
| 预约服务 reservation | m1 | G1 个人预约、G4 空间资源 | `db/m1/g1/`、`db/m1/g4/` |
| 审批服务 approval | m2 | G2 整室审批、G5 身份权限与参数 | `db/m2/g2/`、`db/m2/g5/` |
| 维修服务 maintenance | m3 | G3 报修维修、G6 通知审计 | `db/m3/g3/`、`db/m3/g6/` |

上表是全项目约定。本地 G5 工作目录目前只实现审批服务及 G5 的迁移，不代表其他目录或模块已实现。GitHub 的 G6 公共骨架与本地启动工程尚未完全整合，不能直接把两边的构建命令混用。

一张表只由所属组写入。即使 G2/G5 共用 m2，G2 也通过 G5 的公开接口查询账户和权限，不直接查询或修改 G5 表。跨服务只保存外部 ID 和必要历史快照，不跨 schema 建外键、级联删除或直接联表查询；同组内部可以建立外键。不要重复建立账户、角色、房间等已有事实的主表。

## 2. 新电脑如何创建现有表

以下命令适用于当前本地 G5 工程；工作目录为仓库根目录。

1. 准备 PostgreSQL 17、JDK 21，以及可连接的独立开发数据库。PostgreSQL 实例、数据库和登录角色须预先由环境负责人创建；Flyway 不负责安装数据库或创建数据库登录角色。
2. 按 [审批服务说明](../services/approval/README.md#配置) 设置运行连接和迁移连接。必须提供 `DB_USERNAME`、`DB_PASSWORD`、`DB_MIGRATION_USERNAME`、`DB_MIGRATION_PASSWORD`；`DB_URL` 和 `DB_MIGRATION_URL` 必须指向预期环境。密码通过私有配置或交互输入提供，不写进 SQL、仓库和公开命令记录。
3. 构建并启动服务。Windows 下先停止使用该 JAR 的本机服务（本启动器可用 `scripts/Stop-CampusLocal.ps1`），或在隔离源码副本中构建；不要覆盖运行中的 JAR：

```powershell
.\services\approval\mvnw.cmd -f .\services\approval\pom.xml -B -ntp clean verify
& "$env:JAVA_HOME\bin\java.exe" -jar .\services\approval\target\approval-service-0.1.0-SNAPSHOT.jar
```

启动时，唯一 Flyway 实例使用迁移账户，将打包到 `classpath:db/migration` 的 m2 迁移按版本执行，记录在 `m2.flyway_schema_history`。普通 `verify` 只包含默认构建检查，不能替代真实数据库集成测试。

4. 在数据库工具中连接同一个开发库，执行以下**只读查询**核对结果：

```sql
SELECT version, description, success
FROM m2.flyway_schema_history
WHERE version IS NOT NULL
ORDER BY installed_rank;

SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'm2' AND table_type = 'BASE TABLE'
ORDER BY table_name;
```

当前 G5 有 6 个版本迁移、17 张应用表，此外还有 Flyway 历史表；其他组接入后数量会增加。具体表用途与脚本位置见 [G5 表清单](m2/g5/README.md#现有应用表清单)。仅建表成功不表示已经有可登录账户，管理员与角色初始化见 [认证说明](../services/approval/AUTH.md)。

同一 JAR 再次启动时，已成功执行的版本只做校验，不重复执行 `CREATE TABLE`。修改 SQL 后需要重新打包并重启服务；仅修改源码目录中的文件不会更新正在运行的 JAR。

## 3. 新增表或修改已有表

### 3.1 先写设计和影响范围

在任务记录中列明需求、表 owner、字段类型/空值/默认值、主键/唯一约束/外键/检查约束、读写查询和索引、历史保留、初始化数据、兼容旧应用的方式及验证场景。涉及对外数据形状或行为时，同步契约和调用方；涉及公共或跨组 schema 时由相关 owner 评审。

当前物理表结构以 [G5 SQL 文件](m2/g5/) 为准，不能只参照第一版建表脚本：例如账户首登标记的默认值已被第 002 项迁移改为 false，创建意图允许的类型已被第 004 项扩展。新代码必须面向迁移到最新版本后的结构。

### 3.2 创建新的版本文件

文件名格式：

```text
V<UTC日期>_<UTC时间到秒>_<纯数字组号>_<组内序号>__G<组号>_<描述>.sql
```

例如 `V20261104_090000_5_007__G5_add_example.sql` **仅为命名示例**，不要照抄时间或认定 007 永远可用。新建前检查目标主干和本分支同一 schema 下的全部组目录，选择晚于主干最大版本且不重复的完整版本键，并分配本组下一序号。时间使用 UTC，可在 PowerShell 查看：

```powershell
(Get-Date).ToUniversalTime().ToString('yyyyMMdd_HHmmss')
```

G5 文件只放 `db/m2/g5/`。G2/G5 共用同一 Flyway 历史与版本排序，不是各自从 1 独立执行一套迁移。已执行或已合入的迁移不改名、不编辑、不删除；后续变化写新迁移。未发布且从未执行的分支迁移可按基线重新编号；只在个人隔离库执行过的版本，须先明确隔离库的重建方案，不能直接改名后继续使用原历史。共享或已发布环境通过新迁移演进，不用 `outOfOrder` 掩盖版本落后。

### 3.3 SQL 写法示例

以下是**说明用模板**，不是待执行的功能，也不应原样放入迁移目录。真实表名和字段必须来自已审阅的业务设计。

```sql
CREATE TABLE m2.g5_example (
    id uuid PRIMARY KEY,
    code varchar(40) NOT NULL,
    owner_account_id uuid NOT NULL REFERENCES m2.account(id),
    version bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT g5_example_code_uk UNIQUE (code),
    CONSTRAINT g5_example_version_ck CHECK (version > 0)
);

CREATE INDEX g5_example_owner_idx ON m2.g5_example(owner_account_id);
COMMENT ON TABLE m2.g5_example IS '说明用示例，须替换为实际业务设计';
```

- 使用小写下划线命名，显式写 schema。技术文档里的逻辑表族与实际表名要有对应说明，不能只靠 `g5_` 前缀判断归属。
- 可变聚合使用 UUID 主键、正整数 version 和创建/更新时间；字典、单例、关联及追加历史等结构按业务设计选择主键，现有表并非全部套用同一模板。
- 时间使用 `timestamptz`，业务日期/展示按 Asia/Shanghai，区间采用 `[start,end)`。`updated_at DEFAULT` 仅在插入时生效，更新时必须由实际写操作维护；默认值不会自动更新 version。
- 必填、范围和唯一性落实为数据库约束。索引根据实际过滤、排序、关联查询设计；PostgreSQL 外键不会自动给引用列建索引。
- 密码只保存成熟算法的加盐散列；会话与 CSRF 保存摘要，不存原令牌。管理请求摘要含敏感字段时遵循现有 HMAC 方案，不把密码或完整请求写进 JSON、审计或示例。
- 新增非空字段时考虑存量数据：先扩展、按规则回填并核验，再收紧约束。删除/改名字段须覆盖旧应用和回退窗口，不直接把旧字段删掉。
- 不使用 `CREATE TABLE IF NOT EXISTS` 隐藏结构漂移，也不以手工补列替代迁移。版本脚本在 Flyway 历史中只执行一次；应用种子的可重复执行是另一层责任。

### 3.4 处理初始化与权限

结构迁移、系统初始化、测试数据分别管理。G5 权限和六类内置角色由应用幂等种子维护；初始管理员需显式启用并注入私有凭据，不在 SQL 中放默认密码。技术规则确定的配置初值可由迁移插入，后续重启不能覆盖用户已修改的值。验证账号仅用于隔离开发/测试环境，不能随生产迁移导入。

迁移角色负责建表/改表；运行角色仅获本服务 schema 所需的查询与写入权限，不给 DDL。新增表、序列或函数时，要同时核对运行角色所需权限，不能只验证迁移账户能访问。当前本机辅助脚本为开发便利复用了开发账户，不能据此声称测试/生产权限隔离已完成；具体授权由部署负责人按对象 owner 和实际需要配置。数据库权限隔离的是三个服务 schema，组级边界还依靠代码和 SQL 归属检查。

## 4. 如何交付验证

在独立数据库验证，保留真实命令、日志、迁移版本和源码快照对应关系：

| 场景 | 应观察到的结果 |
| --- | --- |
| 空库执行全部迁移 | 得到最新表/约束/索引，Flyway 每项成功 |
| 同一版本重复启动 | 不重复建表、不重复种子、不重置密码和已有绑定/配置 |
| 带旧数据的升级 | 原记录保留，回填正确，新约束成立 |
| 非法/重复数据 | 必填、唯一、外键、范围约束按设计拒绝写入 |
| 真实应用读写 | 实际查询、版本冲突和相关事务/并发场景正确 |
| 受限运行账户 | 可完成所需读写，不能建表/改表，不能访问其他服务 schema |
| 故意失败的迁移 | 隔离环境中服务启动失败，不出现“忽略错误继续运行” |

当前本机的 G5 数据库验收入口为：

```powershell
.\scripts\Test-G5Management.ps1 -EvidenceDirectory .local/db-migration-check
```

该脚本依赖本机 PostgreSQL 工具与私有凭据，实际创建 6 个独立 `pj_g5_auth_it_*` 数据库并运行 `postgres-it`；其他电脑须按 [服务说明](../services/approval/README.md#真实数据库集成测试) 准备环境。新增表还须补对应约束和升级测试，不能认为现有测试会自动覆盖未来的表。不要对正常业务库运行这些测试。

六库是当前测试夹具的隔离方式，不是系统运行所需的数据库数量。也可以设计为一个专用测试库，串行执行各套件并在每套开始前重建测试 schema；当前套件还依赖独立连接和数据，须先调整初始化/清理及隔离断言，不能只把六个连接地址改为相同。

提交评审时附迁移清单、设计/兼容说明、真实验证结果及所需运行权限。后端、契约、迁移、种子和消费方须对应同一候选版本。不要把本文的验证要求理解为这些未来场景已经执行。

## 5. 遇到问题时

| 问题 | 处理方向 |
| --- | --- |
| 数据库/角色不存在或认证失败 | 先核对环境和私有凭据，Flyway 不负责创建 PostgreSQL 数据库角色 |
| 新 SQL 没有被发现 | 确认文件路径、版本文件名、资源打包及当前运行 JAR；不另启第二个迁移器 |
| checksum mismatch | 查明已执行文件与代码版本的差异，恢复原迁移内容并通过新版本修改；不随意 repair 历史 |
| 重复版本或版本落后 | 核对同 schema 下所有组与主干版本，遵循上面的未执行/已执行区分处理 |
| relation already exists | 核对是否连错库、有人手工建表或历史不匹配；不要随手删表或自动 baseline |
| 运行账户 permission denied | 核对实际对象 owner、schema USAGE、表/序列/函数权限，补最小授权而非改用超级用户 |

当前本地配置禁用 Flyway clean、自动 baseline 和 out-of-order，启动时校验迁移。不要为了启动成功绕过校验。已应用的数据变更通常通过前向迁移修复；应用回退要先确认 schema 兼容。需要恢复备份时按技术文档 §12 的写入门禁与增量保留流程执行，不提供通用自动 `down.sql`。
