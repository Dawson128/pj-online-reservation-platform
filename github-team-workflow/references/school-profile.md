# 校园资源平台项目配置

适用于本仓库的课程任务与技术基线 v2。六个人各拥有一个模块；三个后端服务只是部署单元，不是三个所有者。通用 skill 移植到其他项目时按其实际基线重新映射。

## 来源及事实层次

- 课程任务书：持续版本管理、AI 生成变更人工验收后提交、合并请求评审、**代码冲突由人处理，不得交由 AI 自行合并**、tag 追溯、测试失败阻断合并、测试环境自动部署全部模块、生产人工审批与回滚。
- [需求文档 v2](../../需求与技术文档/需求文档v2.md)（需求基线 v1.4）：业务行为。工程过程不在需求正文另编 REQ-ENG。
- [技术文档 v2](../../需求与技术文档/技术文档v2.md) §2、§10–§12：六模块所有权、`gN/<task>`、ENG-01–ENG-17、契约门禁、四组件 manifest。

文档里的“待实现／待验证”保持原状。本 skill 和模板的生成不能改成已通过。使用时声明自己的模块即可，不预先登记六人姓名或 GitHub 账号。

“人工处理冲突、持续提交、测试失败阻断、生产审批、六模块一人一个”来自任务书或技术基线 v2。“至少一位非作者、merge commit”是本 skill 的建议，按其来源表述。

## 六人、六个模块

每人只维护自己的模块。同服务的另一人不是共同所有者，也不能代替对方评审。

| 模块 | 独占职责 | 部署单元 | 独占路径 |
|---|---|---|---|
| G1 个人预约 | 搜索/预约、签到、历史、违约申诉、统计 | reservation-service | `apps/web/src/features/g1/`、`services/reservation/modules/g1/`、`db/m1/g1/`、`contracts/openapi/g1.yaml`、`contracts/events/g1/`、`contracts/local/g1.md` |
| G2 整室审批 | 申请、两级审批、撤销终止、审批工作台 | approval-service | `apps/web/src/features/g2/`、`services/approval/modules/g2/`、`db/m2/g2/`、`contracts/openapi/g2.yaml`、`contracts/events/g2/`、`contracts/local/g2.md` |
| G3 报修维修 | 报修、派改、维修、完工验收、评价、升级 | maintenance-service | `apps/web/src/features/g3/`、`services/maintenance/modules/g3/`、`db/m3/g3/`、`contracts/openapi/g3.yaml`、`contracts/events/g3/`、`contracts/local/g3.md` |
| G4 空间资源 | 房间座位、占用台账、资源事务协调器 | reservation-service | `apps/web/src/features/g4/`、`services/reservation/modules/g4/`、`services/reservation/integration/`、`db/m1/g4/`、`contracts/openapi/g4.yaml`、`contracts/events/g4/`、`contracts/local/g4.md`；`contracts/common` 中的资源类型 |
| G5 身份权限 | 账户会话、角色动作范围、院系、业务参数 | approval-service | `apps/web/src/features/g5/`、`services/approval/modules/g5/`、`db/m2/g5/`、`contracts/openapi/g5.yaml`、`contracts/events/g5/`、`contracts/local/g5.md`；`contracts/common` 中的身份类型 |
| G6 通知审计与工程集成 | 邮件、审计、运行聚合、前端公共框架、构建部署 | maintenance-service | `apps/web/src/features/g6/`、`services/maintenance/modules/g6/`、`db/m3/g6/`、`contracts/openapi/g6.yaml`、`contracts/events/g6/`、`contracts/local/g6.md`；`contracts/common` 中的信封与错误；`apps/web/src/app/` 的 `router.ts`、`route-registry.ts`、`navigation.ts`、`session.ts`、`http.ts`、`polling.ts`；`shared/ui`、`infra`；各服务启动装配文件 |

各组只导出自己的 `routes.ts` 与 `navigation.ts`，由 G6 在唯一注册文件导入。G6 维护各服务启动装配，装配不写领域规则。G4 维护 `ResourceTransactionCoordinator`，G1 入口调用它，G1 不拥有该目录。

其他模块只能引用对方 `api`，不得引用 repository/entity，不得执行其表 SQL。组级隔离靠包可见性、端口、SQL 目录检查和架构测试；数据库角色只隔离 m1/m2/m3 三个 schema，不能当成六人隔离。

接入时核对真实目录。逻辑模块名不冒充已有 GitHub Team。身份只看本次声明的模块。

## 谁必须一起评审

普通 PR 至少一位非作者人工评审。下列变更还要**提供模块与每个消费模块**各自批准，同一服务的搭档不能顶替：

- `contracts/common`：身份类型找 G5，资源类型找 G4，信封与错误找 G6，再加上全部消费者。
- 公共类型、锁序、跨组状态、数据库 schema。
- 一方改了接口，调用方的实现和测试一起改。
- 服务级启动、构建或共享配置同时影响同服务的两个模块时，这两个模块所有者都要看；启动装配的所有者仍是 G6。
- `.github/` 与发布流水线由 G6 维护；各组提供并修复自己的测试和部署探针。G6 不代替其余五组写测试或验收。

CODEOWNERS 同一行多人是“任一批准即可”。逐模块覆盖要另查，不能把 GitHub 的单人批准当成六人都同意。

## 分支、任务顺序与迁移

分支名 `gN/<task>`，例如 `g1/3.2-book-seat`。一分支一项可验收改动，主要落在本人路径。需要改别人的目录时，先得到该模块所有者的评审，不把跨模块 diff 混进无人认领的提交。

依赖顺序只有先后，没有人员日历：

1. 阶段 A：G5、G4、G6 先冻结身份、资源、信封/错误/Shell 契约。
2. 阶段 B：G1、G2、G3 并行；G4 台账与 G6 收件/审计继续。服务间只走公开接口。
3. 阶段 C：接通 G2→G4、G3→G4 真实网络。
4. 阶段 D：发布、迁移、备份、回退和现场演示。

迁移文件名 `V20261024_120000_4_001__G4_description.sql`。版本键是 UTC 到秒、组号 1–6、该组序号；组名只写在双下划线后。同 schema 按完整键排序，合入后不改名、不改校验和。序号只由本组迁移清单分配。合并前看主干该 schema 的最大版本；未发布分支上时间更早的迁移要重编到主干之后。禁止用 outOfOrder 掩盖顺序。m1 由 G1/G4 分目录共写，m2 由 G2/G5，m3 由 G3/G6；每个服务只有一套 Flyway 历史，两组不得各起一个迁移器。

每个任务写明需求小节、owner、接口 ID、允许目录、验收 oracle、反例和回滚。AI 先给方案，人审方案后再实现；提交前由该模块负责人验收 diff 和行为。

## 必须保留的集成约束

- 三服务真实 HTTP。应用库账号只 DML 本 schema。跨模块走接口，不改对方的表。
- G4 协调器是 reservation 唯一资源写事务入口。它在事务外取 G5 事实，在事务内按锁序调用 G1 与 G4 端口。两端口不互调、不开新事务、不访问网络。
- 需求、接口、状态/枚举、迁移、种子、调用方和测试一起版本化。接口有歧义先记录，不在解冲突时发明业务规则。
- 种子分开：G5 管身份与六内置角色，G4 管示例空间。重跑不得覆盖已改密码或真实业务。

## 验证与发布

真实命令从仓库的前端脚本、Java 构建、迁移配置和流水线读取。命令还不存在时记为未验证，不写成通过。

合入主干前的必需检查：类型/编译、schema 例子、OpenAPI 消费契约、数据库迁移、架构导入与 SQL 越界、单元测试和真实 PostgreSQL 集成。改了前端就跑核心流程。CI 不能用“HTTP 200”代替 claim、状态或事件事实。

发布物是四个组件的同一批次：web、reservation、approval、maintenance，加 m1/m2/m3 各自的最高迁移版本。测试环境合入后自动部署全部模块，不能只部署当前组。生产由人工按具体 manifest 审批。G6 协调流水线与 manifest；三服务的运行探针由对应模块所有者提供。扩展迁移和兼容的提供方先于消费者，不按 m1/m2/m3 的名字固定排序。

期末演示仍是五项真实操作：空环境经流水线部署、一次提交触发构建测试、带 schema 变化的部署、人工批准生产并回退、一次 G2→G4 或 G3→G4 的真实网络写回。

本 skill 可用，不代表仓库已配置保护规则、环境已建好或任何业务测试已通过。
