# 六模块接入指南

## 目录与第一次任务
| owner | 后端模块 | schema | 首条建议链路 |
|---|---|---|---|
| G1 | services/reservation/modules/g1 | m1 | 搜索→预约→本人历史→取消 |
| G4 | services/reservation/modules/g4 | m1 | 房座→可用性→来源占用/释放 |
| G2 | services/approval/modules/g2 | m2 | 申请→两级批准→真实占用 |
| G5 | services/approval/modules/g5 | m2 | 身份种子→登录→会话内省→范围校验 |
| G3 | services/maintenance/modules/g3 | m3 | 报修→派单→维修封锁→验收释放 |
| G6 | services/maintenance/modules/g6 | m3 | 事件持久收件→通知→审计 |

当前 G4 已有契约和 m1 SQL；G5 已迁入身份、管理页面和 m2 SQL。其他模块按各自实际状态继续接入。每个模块已经接入服务 Maven 的 source/test source；使用 `cn.edu.campus.<service>.gN.api/application/domain/persistence` 包。Java source/test 放在模块内，勿放入 G6 启动目录。需要模块专用 resources 时由 G6 补接构建入口。

G4 的协调器放在 `services/reservation/integration/src/main/java`，本地事务依赖 G1/G4 api，禁止锁内网络调用。G1 创建、取消、签到必须接协调器。G2/G3 使用真实 G4 内网 HTTP，禁止跨服务直接改表。

## 前端
每组在 `apps/web/src/features/gN/` 实现业务组件，并导出：
```ts
// routes.ts
import type { RouteRecordRaw } from 'vue-router'
export const routes: RouteRecordRaw[] = [/* 本组命名路由，使用 gN- 前缀 */]
// navigation.ts
import type { NavigationItem } from '../../app/route-registry'
export const navigation: NavigationItem[] = [/* { label, to } */]
```
G6 唯一注册器统一发现 g1—g6 routes/navigation。功能就绪后再导出入口，不展示指向空页面的菜单。导航不代表授权，后端每次检查。

通过 `request<T>` 调用公开 API：返回已拆出的 data；错误保留 HTTP 状态、服务端 code 和 traceId。写请求的 key 由业务交互生成并保留，未知结果使用原键/原正文查询或重试；客户端不会自动重试。当前 G5 登录/退出/改密统一使用共享 session。CSRF 放在当前标签页 sessionStorage，刷新通过 me 恢复身份；有 Cookie 而缺 CSRF 时须重新登录，me 不返回新令牌。

会话 401 会清 CSRF 并发 session-expired 事件。G5 已实现登录、保护路由和管理菜单控制；新建/改密为 8—24 字符，默认不强制首登改密。系统管理员需要预约时由管理员显式追加学生角色。

## 当前后端安全边界
approval正常模式由G5 AuthFilter检查持久会话、CSRF或服务凭据，再允许已实现G5路径进入控制器。G6共享库为reservation/maintenance接入真实G5内省和写请求CSRF。新增业务接口时同步声明ApiAccessRule，由G6装配动作门禁；控制器继续按当前房间/院系事实检查对象范围与本人归属，不能只凭某条同名grant放行所有对象。未登记的路径保持拒绝，不得使用通配permitAll。具体配置和示例见shared/auth-client/README.md；测试探针不进入产品jar。新增G2 API仍须接入G5本地身份边界并补行为验证。管理员须显式初始化，无跨环境通用默认密码。

## 迁移与契约
SQL 放 `db/mN/gN/VYYYYMMDD_HHMMSS_N_NNN__GN_description.sql`，UTC 时间键，同 schema 合并前核对顺序。迁移器账号独立，只写本 schema；应用账号没有 schema CREATE 权限。迁移文件会进入所属服务 jar 的 db/migration 子目录，由一个 Flyway 实例递归扫描。

G5 编写身份/角色种子，G4 编写空间种子；G6 没有替其他组创建业务表和演示数据。公共错误、身份、资源与事件契约仍须按技术文档共同评审。当前目录检查不能证明 SQL 语义所有权，人工评审和后续架构测试仍必需。

## 合作门禁
小任务→从最新 main 建 gN 分支→方案→实现/真实验证→提交前人工验收→PR→相关方评审→CI→合并→测试部署。代码冲突由人处理。主干合并权限、CODEOWNERS 和真实账号尚待 leader 配置。
