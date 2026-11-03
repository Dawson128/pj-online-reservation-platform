# 校园资源服务平台

Vue 3 / TypeScript + Java 21 / Spring Boot 4.1.2 + PostgreSQL 17。六人维护六模块，部署为 web 与三个后端服务。

当前为 **G6 工程启动包**：工程装配、前端公共入口、构建和基础访问边界。预约、身份登录、审批、报修、邮件和审计业务均尚未实现；首页明确标记建设中。不要使用本启动包处理真实业务或宣称生产就绪。

## 环境
- Node **24.22.1**（见 `.nvmrc`，不使用本机旧 Node 18）、npm 9 或以上。
- Java 21、Maven 3.9.x；需要 Docker Engine/Desktop 与 Compose v2 才能执行容器验证。
- 镜像及摘要：`infra/images.lock.json`；npm 精确依赖及间接依赖：`package-lock.json`；后端由固定 Spring Boot parent 管理依赖。

## 首次启动：完整本地容器环境
在仓库根目录执行：
```powershell
pwsh -File infra/scripts/init-env.ps1
docker compose --env-file .env -f infra/compose.yaml config --quiet
docker compose --env-file .env -f infra/compose.yaml up --build --wait --wait-timeout 240
node infra/scripts/smoke-compose.mjs
```
打开 http://127.0.0.1:8080 。只发布本机回环入口；数据库和后端未向宿主机发布端口。此配置是开发环境 HTTP，正式环境的 HTTPS、SMTP 和部署凭据需后续配置。

`.env` 自动生成七个独立随机密码，已在 Git/Docker 忽略。不要把 `docker compose config` 的完整展开结果分享或提交（含密码）；使用 `--quiet` 校验。

停止：`docker compose --env-file .env -f infra/compose.yaml down`，保留数据库卷。不要为了改密码删除数据卷；已有卷不会重跑首次初始化，密码变更应按运维流程完成。

## 前端开发
```powershell
npm ci --no-audit --no-fund
npm run dev
```
打开 http://127.0.0.1:5173 。Vite 仅把 `/api/m1`、`m2`、`m3` 转发到 8081—8083；前端不直接访问内网接口。

## 后端开发
正常运行需要真实 PostgreSQL 及初始化角色。可先仅启动数据库并显式发布本地端口：
```powershell
docker compose --env-file .env -f infra/compose.yaml -f infra/compose.host-db.yaml up -d postgres
mvn -B -ntp verify
```
每个服务设置 `DATABASE_PASSWORD`（对应 APP_MN_PASSWORD）和 `MIGRATION_PASSWORD`（对应 MIGRATE_MN_PASSWORD）后运行：
```powershell
java -jar services/reservation/target/reservation-service-0.1.0-SNAPSHOT.jar
```
approval/maintenance 以各自 jar 和 m2/m3 的独立密码启动。默认 URL 是 localhost:5432/campus?currentSchema=mN。若使用非默认端口，设置 `DATABASE_URL`。

**没有数据库，只验证装配时**：
```powershell
java -jar services/reservation/target/reservation-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=shell
```
三个服务默认端口 8081、8082、8083，默认只绑定 127.0.0.1。`/actuator/health/liveness` 表示进程存活；不表示已通过 G5 鉴权、资源依赖或完整业务就绪检查。shell profile 禁用数据库和迁移，严禁用于发布验收。

## 验证
```powershell
npm run check
npm test
npm run build
mvn -B -ntp verify
```
GitHub `Bootstrap CI` 会运行上述检查、构建全部容器，在独立 PostgreSQL 验证启动/角色/迁移历史，并测试网关边界。汇总检查名 `bootstrap-required`，需管理员在服务器设置为 required 才能强制阻止合并。当前只覆盖启动包；业务契约、并发、故障恢复和端到端流程由各 owner 后续补入。

## 六人接入
阅读 [模块接入指南](docs/collaboration/module-onboarding.md)、[G6 启动包计划](docs/tasks/G6-BOOTSTRAP.md) 与 [实施验证记录](docs/verification/G6-BOOTSTRAP.md)。

`AGENTS.md` 是项目约束；`github-team-workflow/` 是团队协作 skill。Git 分支 `gN/<task>`，通过 PR 持续合并。首次 CODEOWNERS 及各组真实账号、相关方评审、主干保护和发布环境仍需配置，不以示例文件代替实际执行。
