# G6 启动包实施与验证记录

验证日期：2026-11-03（Asia/Shanghai）。模块 G6，任务分支 `g6/project-bootstrap`，基准 `ec5002cab007007aa319d5063f2d85864f36bc48`。当前变更未提交，提交前人工验收、非作者 PR 评审均待完成。

## 已实现范围

- 一个 Vue/TypeScript 应用，六组路由统一注册，G6 首页/404、内存 CSRF 接口、统一 HTTP 错误、取消和可见性轮询基础。
- 三个 Spring Boot 4.1.2 服务，六模块空目录及 Maven 源码/测试入口，保留各组业务所有权。
- 安全启动边界：只开放最小 liveness，业务/internal/其他 actuator 拒绝；未实现 G5 会话鉴权，不生成默认账户。
- PostgreSQL 17 开发编排：三个 schema、六个独立应用/迁移角色、一服务一套 Flyway；随机密码只在被忽略的本地 .env。
- 固定 npm 依赖、锁文件、容器镜像真实摘要及 Actions 提交摘要。
- Bootstrap CI：前端检查/测试/构建、三个后端验证、全组件临时 Compose 验证、失败阻断汇总。CI 文件已编写，尚未在 GitHub 运行。
- 项目规则、接入指南、PR/任务模板、公共评审及后续任务清单。

## 实际执行结果

| 验证 | 结果 | 具体边界 |
|---|---|---|
| npm install（精确版本） | 通过 | 生成 package-lock.json |
| npm run check | 通过 | 迁移命名/版本唯一、跨模块 Java import 基础检查；当前 0 条业务 SQL，不证明完整 SQL 语义所有权 |
| npm test | 9 项通过 | 原幂等键及 CSRF、版本字符串、未知写结果不重试、401 清状态、非法路径、非契约响应 |
| npm run build | 通过 | vue-tsc 和 Vite 生产构建 |
| mvn -B -ntp verify | 三服务通过，6 项测试通过 | shell profile 装配和访问边界测试；本机依赖缓存位于工作区外层 tmp/maven |
| 真实 PostgreSQL 17.9 | 通过 | 隔离临时数据库，执行 Compose 初始化文件中的同一 SQL；三个服务正常数据库模式启动；三份 Flyway 历史存在；应用账号无 CREATE、所有跨 schema USAGE 检查被拒绝 |
| 桌面/手机浏览器 | 通过 | 系统 Edge headless，1440px/390px：首页和404可访问，无横向溢出、无 pageerror，截图留在工作区 tmp |
| YAML 解析 | 通过 | Compose、host-db override、CI、Issue 模板，无重复键；不能替代 docker compose config |
| 故意破坏测试 | 符合预期 | 临时将 Idempotency-Key 写为错误头，行为断言失败；恢复原代码后9项测试通过 |
| Git 对象校验 | 通过 | 下载真实 shallow pack，index-pack/cat-file/fsck 校验通过，没有伪造基线提交 |

验证使用 Node 24.22.1、Java 21、Maven 3.9.5。电脑默认 Node 为18，首次临时变异复验误用了旧 Node，已改用 Node24 重新执行并确认断言失败和恢复后通过；不把环境启动失败算作有效变异证据。

## 已解决的问题与真实限制

1. Git 直连多次超时/重置：仓库快照可下载，最终通过同仓库标准只读 Git HTTP 协议取得真实对象并建立分支。后续普通 fetch/push 仍需确认网络和 GitHub 认证。本机 Git Credential Manager 未列出 GitHub 登录账号；未读取或打印凭据。
2. 首轮后端测试发现配置类与 FilterChain Bean 同名，已更名修复并复验通过。
3. Windows PostgreSQL 对中文数据路径初始化失败，临时数据库改在系统临时目录的 ASCII 路径，保留失败日志，不修改系统编码。测试后二进制/数据仅保留为本机 QA 产物，全部数据库和后端测试进程已停止。
4. Compose 健康命令最初引号多转义，YAML 解析检查时发现并修正。Docker 本机未安装，因此容器内实际命令仍待 CI 验证。
5. 默认 Spring Security 自动用户生成已禁用，不提供可误用的默认密码；G5 真实认证接线为下一批任务。

## 明确未完成/未验证

- Docker Compose 实际构建、网关和容器整体启动未在本机执行；GitHub Actions 尚未执行。
- GitHub main 保护、CODEOWNERS 实际账号、逐组门禁、成员权限和环境设置未核实或配置。
- 无提交、push、PR、tag、主干合并或远程部署。无人工验收签名。
- 业务契约、G1—G5 功能、真实会话/CSRF恢复/登录跳转、G6 通知持久化和邮件/审计均未完成。
- 技术文档规定的 internal readiness、跨服务业务、并发/故障恢复尚未验证，liveness 不替代这些检查。
- 测试服务器自动部署、生产 HTTPS/审批、manifest、备份与回退演练待后续任务。一次性 Compose CI 不是共享测试环境发布。

## 人工验收入口

阅读根 README、`docs/tasks/G6-BOOTSTRAP.md`、`docs/collaboration/module-onboarding.md`，检查源代码差异及上述真实验证边界。提交前由 G6 负责人验收；服务装配/公共框架须相关模块评审。按仓库 skill §4.3，不能用一般开发授权或 AI 自己的测试结果代替人工验收。
